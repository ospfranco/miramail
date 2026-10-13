import Foundation

/// Result values from RFC 8601 Authentication-Results.
private enum AuthResult: String {
    case pass, fail
}

private enum AuthMethod: String {
    case dmarc, dkim, spf
}

/// The sender checks, so the evaluation can tell which one raised a problem. Each has a fixed verdict and a plain
/// description for the email header, written for someone who doesn't know how email works.
enum SenderCheckLabel: String, CaseIterable {
    case verified = "Sender verified"
    case notVerified = "Sender not verified"
    case failed = "Sender verification failed"
    case known = "Known sender"
    case firstMessage = "First message from this sender"
    case historyUnknown = "Sender history unknown"

    var verdict: Verdict {
        switch self {
        case .verified, .known: .safe
        case .notVerified, .firstMessage, .historyUnknown: .warning
        case .failed: .danger
        }
    }

    var description: String {
        switch self {
        case .verified:
            "Gmail confirmed that this email really comes from the address it shows."
        case .notVerified:
            "Gmail couldn't confirm that this email really comes from the address it shows. That's often harmless, "
                + "but someone could be pretending to be this sender. Be careful if it asks you to do something or to share information."
        case .failed:
            "This email pretends to come from an address that didn't send it, so it's probably a fake. Don't open its links "
                + "or reply with personal details. If it claims to be from someone you know, contact them another way."
        case .known:
            "You've exchanged emails with this address before."
        case .firstMessage:
            "This is the first email you get from this address. If it asks you to do something or to share information, "
                + "be careful before you continue."
        case .historyUnknown:
            "Trustmail couldn't check whether this address has emailed you before, for example because you're offline. "
                + "Be careful if it asks you to do something or to share information."
        }
    }
}

extension SecurityCheck {
    init(_ label: SenderCheckLabel) {
        self.init(label: label.rawValue, detail: label.description, verdict: label.verdict)
    }
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
        if results.isEmpty { return SecurityCheck(.notVerified) }

        let dmarc = results.first { $0.method == AuthMethod.dmarc.rawValue }
        if dmarc?.result == AuthResult.pass.rawValue { return SecurityCheck(.verified) }
        if dmarc?.result == AuthResult.fail.rawValue { return SecurityCheck(.failed) }

        // No conclusive DMARC (the domain has no policy, or a lookup error): judge SPF/DKIM directly.
        let alignedDkim = results.contains {
            $0.method == AuthMethod.dkim.rawValue && $0.result == AuthResult.pass.rawValue
                && aligned(domainOf($0.properties["header.d"] ?? $0.properties["header.i"]), fromDomain)
        }
        let alignedSpf = results.contains {
            $0.method == AuthMethod.spf.rawValue && $0.result == AuthResult.pass.rawValue && aligned(domainOf($0.properties["smtp.mailfrom"]), fromDomain)
        }
        if alignedDkim || alignedSpf { return SecurityCheck(.verified) }

        let failed = results.contains {
            ($0.method == AuthMethod.dkim.rawValue || $0.method == AuthMethod.spf.rawValue) && $0.result == AuthResult.fail.rawValue
        }
        return SecurityCheck(failed ? .failed : .notVerified)
    }

    /// Whether you've had other mail from or to `address`: a sender already seen in the inbox counts even after
    /// their emails were deleted, otherwise Gmail's search decides.
    static func isKnownSender(_ address: String, excluding messageId: String, mail: MailService, senders: KnownSenders) async throws -> Bool {
        if senders.knows(address, besides: messageId) { return true }
        return try await mail.hasCorrespondence(with: address, excluding: messageId)
    }

    static func checkSenderHistory(_ message: MessageDetail, mail: MailService, senders: KnownSenders = KnownSenders()) async -> SecurityCheck {
        do {
            let known = try await isKnownSender(message.fromAddress, excluding: message.id, mail: mail, senders: senders)
            return SecurityCheck(known ? .known : .firstMessage)
        } catch {
            return SecurityCheck(.historyUnknown)
        }
    }

    static func checkSender(_ message: MessageDetail, mail: MailService, senders: KnownSenders = KnownSenders()) async -> [SecurityCheck] {
        [checkSenderDomain(message), await checkSenderHistory(message, mail: mail, senders: senders)]
    }
}

extension MessageSummary {
    /// The sender failed Gmail's checks (the email may be forged), so the inbox marks it like an unsafe link.
    var senderFailed: Bool {
        SenderChecks.checkSenderDomain(fromAddress: bareAddress(from), authenticationResults: authenticationResults).verdict == .danger
    }
}
