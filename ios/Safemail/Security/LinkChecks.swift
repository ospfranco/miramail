import Foundation

/// Earliest issuance among a domain's certificates in Certificate Transparency logs.
/// A nil history means the lookup failed; `firstIssued == nil` means no certificates are logged.
struct CertificateHistory: Sendable, Equatable {
    let firstIssued: Date?
}

enum TlsProbeResult: Sendable {
    case ok(trusted: Bool, trustError: String?, leaf: Certificate)
    case failure(String)
}

struct PublicDnsAnswer: Sendable, Equatable {
    let exists: Bool
    let addresses: [String]
}

/// A tiny async cache so concurrent callers share one in-flight request per key.
/// Newsletters link the same few hosts many times, and the same sender keeps sending them.
actor TTLCache<Value: Sendable> {
    private let ttl: TimeInterval
    private var entries: [String: (expires: Date, task: Task<Value, Never>)] = [:]

    init(ttl: TimeInterval = 10 * 60) { self.ttl = ttl }

    func value(for key: String, load: @escaping @Sendable () async -> Value) async -> Value {
        if let entry = entries[key], entry.expires > Date() { return await entry.task.value }
        let task = Task { await load() }
        entries[key] = (Date().addingTimeInterval(ttl), task)
        return await task.value
    }
}

enum LinkChecks {
    static let timeout: TimeInterval = 5
    private static let dayInterval: TimeInterval = 24 * 60 * 60
    /// A domain whose certificate history is younger than this is likely freshly registered (throwaway).
    static let newDomainDays = 15.0
    /// A subdomain certificate this young, on an established domain, suggests a just-created phishing subdomain.
    static let newSubdomainCertificateDays = 2.0

    /// Organizations that issue the vast majority of publicly trusted TLS certificates, matched as a
    /// case-insensitive prefix of the leaf certificate's issuer O (falling back to CN).
    /// Brands like RapidSSL, GeoTrust and Thawte issue under "DigiCert Inc".
    static let knownCertificateIssuers = [
        "Let's Encrypt",
        "Google Trust Services",
        "DigiCert",
        "Sectigo",
        "ZeroSSL",
        "GlobalSign",
        "Amazon",
        "Microsoft Corporation",
        "GoDaddy.com",
        "Starfield Technologies",
        "Entrust",
        "IdenTrust",
        "Cloudflare",
        "Apple Inc",
        "SSL Corporation",
        "Certainly",
        "Actalis",
        "Buypass",
    ]

    // MARK: - Network (cached)

    private static let tlsCache = TTLCache<TlsProbeResult>()
    private static let historyCache = TTLCache<CertificateHistory?>()
    private static let checksCache = TTLCache<[LinkCheck]>()

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    private static func probeTls(_ host: String) async -> TlsProbeResult {
        await tlsCache.value(for: host) {
            do {
                let result = try await TlsInspector.inspect(host: host, timeout: timeout)
                guard let leaf = result.chain.first else { return .failure("Server sent no certificate") }
                return .ok(trusted: result.trusted, trustError: result.trustError, leaf: try parseCertificate(der: leaf))
            } catch {
                return .failure(error.localizedDescription)
            }
        }
    }

    /// Asks Cert Spotter for the domain's (and its subdomains') certificates. Every publicly trusted
    /// certificate must be logged to CT, so the oldest one approximates how long the domain has served
    /// HTTPS. Cert Spotter only returns unexpired certificates, so this is a lower bound on the
    /// domain's age, which is enough to tell a days-old domain from an established one.
    ///
    /// TODO(backend): unauthenticated Cert Spotter allows 100 requests/hour per IP. Move this lookup
    /// behind our backend with an API key and shared cache. See FUTURE_CHANGES.md.
    private static func fetchCertificateHistory(_ domain: String) async -> CertificateHistory? {
        await historyCache.value(for: domain) {
            guard let url = URL(string: "https://api.certspotter.com/v1/issuances?domain=\(encodeURIComponent(domain))&include_subdomains=true") else {
                return nil
            }
            struct Issuance: Decodable { let not_before: String }
            do {
                let (data, response) = try await session.data(from: url)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
                // Results come oldest-logged first, so the first page holds the earliest issuances.
                let issuances = try JSONDecoder().decode([Issuance].self, from: data)
                if issuances.isEmpty { return CertificateHistory(firstIssued: nil) }
                let dates = issuances.compactMap { parseIsoDate($0.not_before) }
                guard let earliest = dates.min() else { return nil }
                return CertificateHistory(firstIssued: earliest)
            } catch {
                return nil
            }
        }
    }

    private static func parseIsoDate(_ string: String) -> Date? {
        let plain = ISO8601DateFormatter()
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return plain.date(from: string) ?? fractional.date(from: string)
    }

    /// Looks up A and AAAA over DNS-over-HTTPS, bypassing the device's (possibly tampered) resolver.
    private static func resolvePublicly(_ host: String) async -> PublicDnsAnswer? {
        struct Response: Decodable {
            struct Answer: Decodable {
                let type: Int
                let data: String
            }
            let Status: Int
            let Answer: [Answer]?
        }
        let recordTypeA = 1
        let recordTypeAAAA = 28
        let nxDomain = 3

        func query(_ type: Int) async throws -> Response {
            var request = URLRequest(url: URL(string: "https://cloudflare-dns.com/dns-query?name=\(encodeURIComponent(host))&type=\(type)")!)
            request.setValue("application/dns-json", forHTTPHeaderField: "accept")
            let (data, _) = try await session.data(for: request)
            return try JSONDecoder().decode(Response.self, from: data)
        }

        do {
            async let a = query(recordTypeA)
            async let aaaa = query(recordTypeAAAA)
            let answers = try await [a, aaaa]
            let addresses = answers.flatMap { ($0.Answer ?? []).filter { $0.type == recordTypeA || $0.type == recordTypeAAAA }.map(\.data) }
            return PublicDnsAnswer(exists: answers[0].Status != nxDomain, addresses: addresses)
        } catch {
            return nil
        }
    }

    // MARK: - Decisions (pure, unit-tested)

    /// Canonical form so the two resolvers' answers can be compared (IPv6 zero-compression, scope ids).
    static func normalizeIp(_ ip: String) -> String {
        let address = (ip.components(separatedBy: "%").first ?? ip).lowercased()
        if let match = address.firstMatch(of: /([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)$/) { return String(match.1) }
        guard address.contains(":") else { return address }

        let halves = address.components(separatedBy: "::")
        let headGroups = halves[0].isEmpty ? [] : halves[0].components(separatedBy: ":")
        let groups: [String]
        if halves.count > 1 {
            let tailGroups = halves[1].isEmpty ? [] : halves[1].components(separatedBy: ":")
            let missing = max(0, 8 - headGroups.count - tailGroups.count)
            groups = headGroups + Array(repeating: "0", count: missing) + tailGroups
        } else {
            groups = headGroups
        }
        return groups.map { String(Int($0, radix: 16) ?? 0, radix: 16) }.joined(separator: ":")
    }

    /// Loopback, private, link-local, CGNAT, multicast and other addresses a public site never uses.
    static func isReservedIp(_ ip: String) -> Bool {
        if let v4 = ip.wholeMatch(of: /([0-9]+)\.([0-9]+)\.[0-9]+\.[0-9]+/) {
            let a = Int(v4.1) ?? 0
            let b = Int(v4.2) ?? 0
            return a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && (64...127).contains(b))
                || (a == 169 && b == 254)
                || (a == 172 && (16...31).contains(b))
                || (a == 192 && b == 168)
        }
        return ip == "0:0:0:0:0:0:0:1" || ip == "0:0:0:0:0:0:0:0"
            || ip.hasPrefix("fc") || ip.hasPrefix("fd")
            || ["fe8", "fe9", "fea", "feb"].contains { ip.hasPrefix($0) }
    }

    /// Compares the device's DNS answer with a public DNS-over-HTTPS answer. Different addresses alone
    /// are normal for CDNs (answers vary by resolver location), so a mismatch only counts as spoofing
    /// when the server can't also prove its identity with a valid certificate.
    static func checkDns(host: String, deviceAddresses: [String], publicDns: PublicDnsAnswer?, tls: TlsProbeResult?) -> LinkCheck {
        let kind = LinkCheckKind.dnsSpoofing
        let deviceIps = deviceAddresses.map(normalizeIp)

        guard let publicDns else {
            return LinkCheck(kind: kind, verdict: .warning, detail: "Couldn't reach public DNS to compare answers.")
        }
        let publicIps = publicDns.addresses.map(normalizeIp)

        if deviceIps.isEmpty && publicIps.isEmpty {
            return LinkCheck(kind: kind, verdict: .warning, detail: "\(host) doesn't exist or has no addresses.")
        }
        if !publicDns.exists && !deviceIps.isEmpty {
            return LinkCheck(kind: kind, verdict: .danger, detail: "Your network resolves \(host), but public DNS says the domain doesn't exist.")
        }
        if deviceIps.contains(where: isReservedIp) && !publicIps.contains(where: isReservedIp) {
            let reserved = deviceIps.first(where: isReservedIp) ?? ""
            return LinkCheck(kind: kind, verdict: .danger, detail: "Your network points \(host) to a private address (\(reserved)) instead of its public one.")
        }
        if !publicIps.isEmpty && !deviceIps.contains(where: publicIps.contains) {
            if case .ok(let trusted, _, _)? = tls, trusted {
                return LinkCheck(kind: kind, verdict: .safe, detail: "DNS answers differ (normal for CDNs), but the server proved its identity with a valid certificate.")
            }
            return tls != nil
                ? LinkCheck(kind: kind, verdict: .danger, detail: "Your network and public DNS disagree on this domain's address, and the server can't prove its identity.")
                : LinkCheck(kind: kind, verdict: .warning, detail: "Your network and public DNS disagree on this domain's address, and the link isn't encrypted to confirm it.")
        }
        return LinkCheck(kind: kind, verdict: .safe, detail: "Your network and public DNS agree on the address.")
    }

    private static func ageInDays(_ date: Date, now: Date) -> Double {
        now.timeIntervalSince(date) / dayInterval
    }

    static func formatAge(_ days: Double) -> String {
        if days < 1 { return "\(max(1, Int((days * 24).rounded()))) hours" }
        return "\(Int(days.rounded(.down))) days"
    }

    static func checkDomainAge(domain: String, history: CertificateHistory?, encrypted: Bool, now: Date = Date()) -> LinkCheck {
        let kind = LinkCheckKind.domainAge
        guard let history else {
            return LinkCheck(kind: kind, verdict: .warning, detail: "Couldn't look up \(domain)'s certificate history.")
        }
        guard let firstIssued = history.firstIssued else {
            // Every trusted certificate must be logged, so an HTTPS site with none is brand new.
            // An HTTP-only site may simply never have had one.
            return encrypted
                ? LinkCheck(kind: kind, verdict: .danger, detail: "\(domain) has no certificates in public logs. It's likely brand new.")
                : LinkCheck(kind: kind, verdict: .warning, detail: "\(domain) has never had an HTTPS certificate, so its age can't be checked.")
        }
        let age = ageInDays(firstIssued, now: now)
        return age < newDomainDays
            ? LinkCheck(kind: kind, verdict: .danger, detail: "\(domain)'s first certificate was issued only \(formatAge(age)) ago. New domains are often used for phishing.")
            : LinkCheck(kind: kind, verdict: .safe, detail: "\(domain) has had certificates for at least \(formatAge(age)).")
    }

    static func checkTls(host: String, apex: String, hostTls: TlsProbeResult, apexTls: TlsProbeResult?, now: Date = Date()) -> [LinkCheck] {
        guard case .ok(let trusted, let trustError, let leaf) = hostTls else {
            guard case .failure(let error) = hostTls else { return [] }
            return [LinkCheck(kind: .tlsConnection, verdict: .warning, detail: "Couldn't open a secure connection to check the certificate (\(error)).")]
        }
        var checks: [LinkCheck] = []

        let matches = certificateMatchesHost(leaf, host: host)
        let otherSite = leaf.dnsNames.prefix(3).joined(separator: ", ")
        checks.append(LinkCheck(
            kind: .certificateHostname,
            verdict: matches ? .safe : .danger,
            detail: matches
                ? "Certificate is valid for \(host)."
                : "Certificate is for \(otherSite.isEmpty ? (leaf.subjectCommonName ?? "another site") : otherSite), not \(host)."
        ))

        let issuer = leaf.issuerOrg ?? leaf.issuerCommonName ?? "Unknown issuer"
        let known = knownCertificateIssuers.contains { issuer.lowercased().hasPrefix($0.lowercased()) }
        checks.append(LinkCheck(
            kind: .certificateIssuer,
            verdict: !trusted ? .danger : known ? .safe : .warning,
            detail: !trusted
                ? "Your device doesn't trust this certificate\(trustError.map { ": \($0)" } ?? ".")"
                : known
                    ? "Issued by \(issuer)."
                    : "Issued by \(issuer), which isn't a common certificate authority."
        ))

        if host != apex, case .ok(_, _, let apexLeaf)? = apexTls {
            let sameCertificate = apexLeaf.der == leaf.der
            let hostAge = ageInDays(leaf.notBefore, now: now)
            let fresh = !sameCertificate && hostAge < newSubdomainCertificateDays
            checks.append(LinkCheck(
                kind: .subdomainCertificate,
                verdict: fresh ? .danger : .safe,
                detail: sameCertificate
                    ? "\(host) uses the same certificate as \(apex)."
                    : fresh
                        ? "\(host) got its own certificate only \(formatAge(hostAge)) ago, on an otherwise established domain."
                        : "\(host) has its own certificate, issued \(formatAge(hostAge)) ago."
            ))
        }

        return checks
    }

    // MARK: - Entry point

    private static func runChecks(host: String, encrypted: Bool) async -> [LinkCheck] {
        let apex = PublicSuffixList.shared.registrableDomain(of: host) ?? host
        async let hostTls: TlsProbeResult? = encrypted ? probeTls(host) : nil
        async let apexTls: TlsProbeResult? = encrypted && apex != host ? probeTls(apex) : nil
        async let history = fetchCertificateHistory(apex)
        let (hostResult, apexResult, historyResult) = await (hostTls, apexTls, history)

        async let device = TlsInspector.resolve(host: host)
        async let publicDns = resolvePublicly(host)
        let (deviceAddresses, publicAnswer) = await (device, publicDns)
        let dns = checkDns(host: host, deviceAddresses: deviceAddresses, publicDns: publicAnswer, tls: hostResult)

        var checks = [dns, checkDomainAge(domain: apex, history: historyResult, encrypted: encrypted)]
        if let hostResult { checks += checkTls(host: host, apex: apex, hostTls: hostResult, apexTls: apexResult) }
        return checks
    }

    /// DNS and TLS checks for a link's host. Only performs a TLS handshake; never requests the URL itself.
    ///
    /// TODO(backend): these lookups run from the user's device, so the link's server sees the user's IP
    /// and when they opened the email (per-recipient subdomains turn this into a read receipt), and
    /// Cloudflare sees every looked-up hostname. Run them from our backend instead. See FUTURE_CHANGES.md.
    static func runNetworkChecks(host: String, encrypted: Bool) async -> [LinkCheck] {
        await checksCache.value(for: "\(encrypted ? "https" : "http")://\(host)") {
            await runChecks(host: host, encrypted: encrypted)
        }
    }
}
