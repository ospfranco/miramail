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

    /// What the email header shows: the checks that found a problem, unsafe ones first.
    var findings: [SecurityCheck] {
        checks.filter { $0.verdict == .danger } + checks.filter { $0.verdict == .warning }
    }
}

/// The finding about the email's links, only there when some look unsafe or suspicious.
enum LinkFindingLabel: String, CaseIterable {
    case unsafe = "Unsafe links"
    case suspicious = "Suspicious links"

    var verdict: Verdict {
        switch self {
        case .unsafe: .danger
        case .suspicious: .warning
        }
    }

    var description: String {
        switch self {
        case .unsafe:
            "Some links in this email may lead to a fake or harmful website. Don't open the links marked with a red ✕."
        case .suspicious:
            "Some links in this email look unusual. Tap one marked with a yellow ! to see why before anything opens."
        }
    }
}

extension SecurityCheck {
    init(_ label: LinkFindingLabel) {
        self.init(label: label.rawValue, detail: label.description, verdict: label.verdict)
    }
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
    /// (when it looks like a link), where the link really goes, then why it's risky in plain words (unsafe problems
    /// first, one sentence per kind) and what to do.
    static func linkWarning(_ link: ScannedLink) -> String {
        let shown = hostInText(link.text) != nil ? "Shown as: \(cleanUrl(link.text))" : nil
        let target = "Goes to: \(cleanUrl(link.url))"
        let problems = link.checks.filter { $0.verdict == .danger } + link.checks.filter { $0.verdict == .warning }
        var reasons: [String] = []
        for check in problems where !reasons.contains(check.kind.reason) { reasons.append(check.kind.reason) }
        let advice = link.verdict == .danger
            ? "Don't open it unless you're sure where it goes, and never enter a password or personal details there."
            : "Only open it if you trust where it goes."
        return ([[shown, target].compactMap { $0 }.joined(separator: "\n")] + reasons + [advice]).joined(separator: "\n\n")
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
        // Safe links aren't listed: links are only mentioned when they are the problem.
        let checks = senderChecks + (bad > 0 ? [SecurityCheck(.unsafe)] : []) + (suspicious > 0 ? [SecurityCheck(.suspicious)] : [])
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
            senderFailed: senderChecks.contains { $0.verdict == .danger }
        )
    }
}
