import Foundation

/// Result values from RFC 8601 Authentication-Results.
private enum AuthResult: String {
    case pass, fail
}

private enum AuthMethod: String {
    case dmarc, dkim, spf
}

/// The labels of the sender checks, so the evaluation can tell which one raised a problem.
enum SenderCheckLabel: String {
    case verified = "Sender verified"
    case notVerified = "Sender not verified"
    case failed = "Sender verification failed"
    case known = "Known sender"
    case firstMessage = "First message from this sender"
    case historyUnknown = "Sender history unknown"
}

struct MethodResult: Equatable {
    let method: String
    let result: String
    let properties: [String: String]
}

enum SenderChecks {
    /// Parses Gmail's Authentication-Results header, e.g.
    /// `mx.google.com; dkim=pass header.i=@github.com; spf=pass (...) smtp.mailfrom=x@github.com; dmarc=pass header.from=github.com`
    static func parseAuthenticationResults(_ header: String) -> [MethodResult] {
        var text = header
        // Drop (comments), innermost first so nested parentheses are handled.
        while let comment = text.firstMatch(of: /\([^()]*\)/) { text.replaceSubrange(comment.range, with: " ") }
        let statements = text.components(separatedBy: ";").dropFirst() // first segment is the authserv-id

        return statements.compactMap { statement in
            let words = statement.split(whereSeparator: \.isWhitespace).map(String.init)
            guard let head = words.first, let match = head.wholeMatch(of: /([A-Za-z0-9\-]+)=([A-Za-z]+)/) else { return nil }
            var properties: [String: String] = [:]
            for word in words.dropFirst() {
                guard let eq = word.firstIndex(of: "="), eq != word.startIndex else { continue }
                properties[word[..<eq].lowercased()] = word[word.index(after: eq)...].lowercased()
            }
            return MethodResult(method: match.1.lowercased(), result: match.2.lowercased(), properties: properties)
        }
    }

    private static func domainOf(_ addressOrDomain: String?) -> String? {
        guard let value = addressOrDomain else { return nil }
        var domain = value.lastIndex(of: "@").map { String(value[value.index(after: $0)...]) } ?? value
        if domain.hasSuffix(".") { domain.removeLast() }
        return domain.isEmpty ? nil : domain
    }

    private static func organizationalDomain(_ host: String) -> String {
        PublicSuffixList.shared.registrableDomain(of: host) ?? host
    }

    /// DMARC "relaxed" alignment: same organizational domain (e.g. mail.github.com aligns with github.com).
    private static func aligned(_ a: String?, _ b: String) -> Bool {
        guard let a else { return false }
        return organizationalDomain(a) == organizationalDomain(b)
    }

    static func checkSenderDomain(_ message: MessageDetail) -> SecurityCheck {
        checkSenderDomain(fromAddress: message.fromAddress, authenticationResults: message.authenticationResults)
    }

    static func checkSenderDomain(fromAddress: String, authenticationResults: [String]) -> SecurityCheck {
        let fromDomain = domainOf(fromAddress) ?? "unknown"
        let results = authenticationResults.flatMap(parseAuthenticationResults)

        if results.isEmpty {
            return SecurityCheck(
                label: SenderCheckLabel.notVerified.rawValue,
                detail: "Gmail recorded no authentication results, so \(fromDomain) can't be confirmed as the real sender.",
                verdict: .warning
            )
        }

        let dmarc = results.first { $0.method == AuthMethod.dmarc.rawValue }
        if dmarc?.result == AuthResult.pass.rawValue {
            return SecurityCheck(label: SenderCheckLabel.verified.rawValue, detail: "Gmail confirmed this email really comes from \(fromDomain) (DMARC).", verdict: .safe)
        }
        if dmarc?.result == AuthResult.fail.rawValue {
            return SecurityCheck(
                label: SenderCheckLabel.failed.rawValue,
                detail: "\(fromDomain) says it didn't send this email (DMARC failed). It may be forged.",
                verdict: .danger
            )
        }

        // No conclusive DMARC (the domain has no policy, or a lookup error): judge SPF/DKIM directly.
        let dkimPasses = results.filter { $0.method == AuthMethod.dkim.rawValue && $0.result == AuthResult.pass.rawValue }
        let spfPasses = results.filter { $0.method == AuthMethod.spf.rawValue && $0.result == AuthResult.pass.rawValue }
        let alignedDkim = dkimPasses.first { aligned(domainOf($0.properties["header.d"] ?? $0.properties["header.i"]), fromDomain) }
        let alignedSpf = spfPasses.first { aligned(domainOf($0.properties["smtp.mailfrom"]), fromDomain) }

        if alignedDkim != nil || alignedSpf != nil {
            return SecurityCheck(
                label: SenderCheckLabel.verified.rawValue,
                detail: "Gmail confirmed this email was \(alignedDkim != nil ? "signed" : "sent") by \(fromDomain) (\(alignedDkim != nil ? "DKIM" : "SPF")).",
                verdict: .safe
            )
        }

        let failed = results.contains {
            ($0.method == AuthMethod.dkim.rawValue || $0.method == AuthMethod.spf.rawValue) && $0.result == AuthResult.fail.rawValue
        }
        if failed {
            return SecurityCheck(label: SenderCheckLabel.failed.rawValue, detail: "This email failed \(fromDomain)'s sender checks. It may be forged.", verdict: .danger)
        }

        let relay = domainOf(
            dkimPasses.first?.properties["header.d"] ?? dkimPasses.first?.properties["header.i"] ?? spfPasses.first?.properties["smtp.mailfrom"]
        )
        return SecurityCheck(
            label: SenderCheckLabel.notVerified.rawValue,
            detail: relay.map { "Sent through \($0) on behalf of \(fromDomain), but \(fromDomain) itself isn't verified." }
                ?? "\(fromDomain) couldn't be verified as the real sender.",
            verdict: .warning
        )
    }

    static func checkSenderHistory(_ message: MessageDetail, mail: MailService) async -> SecurityCheck {
        do {
            let known = try await mail.hasCorrespondence(with: message.fromAddress, excluding: message.id)
            return known
                ? SecurityCheck(label: SenderCheckLabel.known.rawValue, detail: "You've exchanged email with this address before.", verdict: .safe)
                : SecurityCheck(label: SenderCheckLabel.firstMessage.rawValue, detail: "You haven't exchanged email with this address before.", verdict: .warning)
        } catch {
            return SecurityCheck(label: SenderCheckLabel.historyUnknown.rawValue, detail: "Couldn't search your past email.", verdict: .warning)
        }
    }

    static func checkSender(_ message: MessageDetail, mail: MailService) async -> [SecurityCheck] {
        [checkSenderDomain(message), await checkSenderHistory(message, mail: mail)]
    }
}

extension MessageSummary {
    /// The sender failed Gmail's checks (the email may be forged), so the inbox marks it like an unsafe link.
    var senderFailed: Bool {
        SenderChecks.checkSenderDomain(fromAddress: bareAddress(from), authenticationResults: authenticationResults).verdict == .danger
    }
}
