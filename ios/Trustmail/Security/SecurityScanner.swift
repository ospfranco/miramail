import Foundation

struct FoundLink: Sendable, Equatable {
    let url: String
    let text: String
    /// Inside the email's footer (see `EmailHtml.footerSelector`).
    var inFooter = false
}

struct ScannedLink: Sendable, Equatable, Identifiable {
    let url: String
    let text: String
    let host: String
    let verdict: Verdict
    let checks: [LinkCheck]
    var inFooter = false

    var id: String { url }
}

struct SecurityCheck: Sendable, Equatable, Identifiable {
    let label: String
    let detail: String
    let verdict: Verdict

    var id: String { label }
}

struct SecurityEvaluation: Sendable, Equatable {
    let verdict: Verdict
    let summary: String
    let checks: [SecurityCheck]
    /// A sender check failed (the email may be forged), so the header marks the sender's domain like an unsafe link.
    var senderFailed = false
    /// Under the verdict in the panel: what made the email risky and what to do about it. None when it looks safe.
    var explanation: String? = nil
}

struct ParsedUrl: Equatable {
    let scheme: String
    let host: String
    let hasUserInfo: Bool
}

enum SecurityScanner {
    private static let urlShorteners = ["bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly", "cutt.ly"]

    /// Parses just the parts the checks need: scheme, host (without credentials or port) and whether credentials are present.
    static func parseUrl(_ url: String) -> ParsedUrl? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let match = trimmed.firstMatch(of: /^([A-Za-z][A-Za-z0-9+.\-]*):(?:\/\/([^\/?#]*))?/) else { return nil }
        let authority = match.2.map(String.init) ?? ""
        let at = authority.lastIndex(of: "@")
        var host = at.map { String(authority[authority.index(after: $0)...]) } ?? authority
        if let port = host.firstMatch(of: /:[0-9]+$/) { host.removeSubrange(port.range) }
        return ParsedUrl(scheme: String(match.1).lowercased(), host: host.lowercased(), hasUserInfo: at != nil)
    }

    /// Pulls a hostname out of link text that looks like a URL or bare domain, e.g. "paypal.com/login".
    static func hostInText(_ text: String) -> String? {
        let pattern = /^\s*(?:https?:\/\/)?(?:www\.)?([a-z0-9\-]+(?:\.[a-z0-9\-]+)*\.[a-z]{2,})(?:[\/:?#]\S*)?\s*$/.ignoresCase()
        return text.wholeMatch(of: pattern).map { String($0.1).lowercased() }
    }

    /// The URL without its query string or fragment, which mostly hold tracking parameters.
    static func cleanUrl(_ url: String) -> String {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.firstIndex(where: { $0 == "?" || $0 == "#" }).map { String(trimmed[..<$0]) } ?? trimmed
    }

    /// The text of the warning shown before opening a link that isn't safe: what the link text shows
    /// (when it looks like a link), where the link really goes, then every problem found.
    static func linkWarning(_ link: ScannedLink) -> String {
        let shown = hostInText(link.text) != nil ? "Shown as: \(cleanUrl(link.text))" : nil
        let target = "Goes to: \(cleanUrl(link.url))"
        let problems = link.checks.filter { $0.verdict != .safe }.map(\.detail)
        return ([[shown, target].compactMap { $0 }.joined(separator: "\n")] + problems).joined(separator: "\n\n")
    }

    static func sameSite(_ a: String, _ b: String) -> Bool {
        func strip(_ host: String) -> String { host.hasPrefix("www.") ? String(host.dropFirst(4)) : host }
        let x = strip(a)
        let y = strip(b)
        return x == y || x.hasSuffix(".\(y)") || y.hasSuffix(".\(x)")
    }

    static func isIpAddress(_ host: String) -> Bool {
        host.wholeMatch(of: /[0-9]{1,3}(\.[0-9]{1,3}){3}/) != nil || host.hasPrefix("[")
    }

    /// Checks that only look at the URL and its visible text.
    static func localChecks(link: FoundLink, scheme: String, host: String, hasUserInfo: Bool) -> [LinkCheck] {
        var checks: [LinkCheck] = []
        func add(_ kind: LinkCheckKind, _ verdict: Verdict, _ detail: String) {
            checks.append(LinkCheck(kind: kind, verdict: verdict, detail: detail))
        }

        if isIpAddress(host) { add(.linkFormat, .danger, "Points to a raw IP address instead of a domain.") }
        if hasUserInfo { add(.linkFormat, .danger, "Hides the real destination behind an \"@\".") }
        if let shownHost = hostInText(link.text), !sameSite(shownHost, host) {
            add(.displayText, .danger, "Text shows \(shownHost) but the link goes to \(host).")
        }
        if scheme == "http" { add(.encryption, .warning, "Not encrypted (http).") }
        if host.split(separator: ".").contains(where: { $0.hasPrefix("xn--") }) {
            add(.lookalikeDomain, .warning, "Uses look-alike (punycode) characters in the domain.")
        }
        if urlShorteners.contains(host) { add(.shortener, .warning, "Shortened link hides the final destination.") }
        return checks
    }

    private static func scanLink(_ link: FoundLink) async -> ScannedLink {
        func result(_ host: String, _ checks: [LinkCheck]) -> ScannedLink {
            ScannedLink(url: link.url, text: link.text, host: host, verdict: Verdict.worst(of: checks.map(\.verdict)), checks: checks, inFooter: link.inFooter)
        }

        guard let parsed = parseUrl(link.url) else {
            return result("", [LinkCheck(kind: .linkFormat, verdict: .warning, detail: "Link could not be parsed.")])
        }
        let (scheme, host, hasUserInfo) = (parsed.scheme, parsed.host, parsed.hasUserInfo)

        if scheme == "mailto" || scheme == "tel" { return result(host, []) }
        if scheme != "http" && scheme != "https" {
            return result(host, [LinkCheck(kind: .linkFormat, verdict: .danger, detail: "Uses a \"\(scheme):\" link, which can run code.")])
        }

        var checks = localChecks(link: link, scheme: scheme, host: host, hasUserInfo: hasUserInfo)
        if !host.isEmpty && !isIpAddress(host) {
            checks += await LinkChecks.runNetworkChecks(host: host, encrypted: scheme == "https")
        }
        return result(host, checks)
    }

    /// Links deduplicated by URL, in first-seen order. A link counts as a footer link only if every copy of it is in the footer.
    static func uniqueLinks(_ links: [FoundLink]) -> [FoundLink] {
        var unique: [FoundLink] = []
        var indexByUrl: [String: Int] = [:]
        for link in links {
            if let index = indexByUrl[link.url] {
                let inFooter = unique[index].inFooter && link.inFooter
                // Keep the entry with visible text, since that's what the mismatch check needs.
                if unique[index].text.isEmpty && !link.text.isEmpty { unique[index] = link }
                unique[index].inFooter = inFooter
            } else {
                indexByUrl[link.url] = unique.count
                unique.append(link)
            }
        }
        return unique
    }

    static func scanLinks(_ links: [FoundLink]) async -> [ScannedLink] {
        let unique = uniqueLinks(links)
        return await withTaskGroup(of: (Int, ScannedLink).self) { group in
            for (index, link) in unique.enumerated() {
                group.addTask { (index, await scanLink(link)) }
            }
            var scanned: [(Int, ScannedLink)] = []
            for await item in group { scanned.append(item) }
            return scanned.sorted { $0.0 < $1.0 }.map(\.1)
        }
    }

    /// With `ignoreFooterLinks`, suspicious footer links don't count; unsafe ones always do.
    static func evaluateEmail(senderChecks: [SecurityCheck], links: [ScannedLink], ignoreFooterLinks: Bool = false) -> SecurityEvaluation {
        let bad = links.filter { $0.verdict == .danger }.count
        let ignored = ignoreFooterLinks ? links.filter { $0.inFooter && $0.verdict == .warning }.count : 0
        let suspicious = links.filter { $0.verdict == .warning }.count - ignored
        let linkCheck = SecurityCheck(
            label: links.isEmpty ? "No links" : "\(links.count) link\(links.count == 1 ? "" : "s") scanned",
            detail: bad > 0 || suspicious > 0
                ? [bad > 0 ? "\(bad) unsafe" : nil, suspicious > 0 ? "\(suspicious) suspicious" : nil].compactMap { $0 }.joined(separator: ", ")
                : "No problems found in links.",
            verdict: bad > 0 ? .danger : suspicious > 0 ? .warning : .safe
        )

        var checks = senderChecks + [linkCheck]
        if ignored > 0 {
            checks.append(SecurityCheck(
                label: "\(ignored) footer link\(ignored == 1 ? "" : "s") ignored",
                detail: "Suspicious links in the footer don't count. You can change this in Settings.",
                verdict: .safe
            ))
        }
        let verdict = Verdict.worst(of: checks.map(\.verdict))
        let summary = switch verdict {
        case .safe: "No problems found"
        case .warning: "Be careful with this email"
        case .danger: "This email looks dangerous"
        }
        return SecurityEvaluation(
            verdict: verdict,
            summary: summary,
            checks: checks,
            senderFailed: senderChecks.contains { $0.verdict == .danger },
            explanation: explanation(senderChecks: senderChecks, unsafeLinks: bad, suspiciousLinks: suspicious)
        )
    }

    /// What made the email risky, in plain words, and what to do about it. Unsafe findings take over from suspicious
    /// ones. None when nothing was found.
    static func explanation(senderChecks: [SecurityCheck], unsafeLinks: Int, suspiciousLinks: Int) -> String? {
        let labels = Set(senderChecks.compactMap { SenderCheckLabel(rawValue: $0.label) })

        var dangers: [String] = []
        if labels.contains(.failed) { dangers.append("failed the sender checks, so it may be forged") }
        if unsafeLinks > 0 { dangers.append(unsafeLinks == 1 ? "has a link that looks unsafe" : "has \(unsafeLinks) links that look unsafe") }
        if !dangers.isEmpty {
            return "This email \(joinedList(dangers)). Don't open its links or reply with personal details. "
                + "If it says it comes from someone you know, contact them another way."
        }

        var senderReasons: [String] = []
        if labels.contains(.notVerified) { senderReasons.append("Gmail couldn't confirm who really sent it") }
        if labels.contains(.firstMessage) { senderReasons.append("it's the first email from this sender") }
        if labels.contains(.historyUnknown) { senderReasons.append("Trustmail couldn't check whether this sender has emailed you before") }
        let linkReasons = suspiciousLinks == 0 ? [] : [suspiciousLinks == 1 ? "1 link looks suspicious" : "\(suspiciousLinks) links look suspicious"]
        if senderReasons.isEmpty && linkReasons.isEmpty { return nil }

        let advice = [
            linkReasons.isEmpty ? nil : "Check where a link really goes before you open it.",
            senderReasons.isEmpty ? nil : "If anything about it seems suspicious, question the email before you act on it.",
        ].compactMap { $0 }
        return "The email looks OK, but \(joinedList(senderReasons + linkReasons)). " + advice.joined(separator: " ")
    }

    /// "a", "a and b", "a, b and c".
    private static func joinedList(_ items: [String]) -> String {
        items.count <= 1 ? items.joined() : items.dropLast().joined(separator: ", ") + " and " + items.last!
    }
}
