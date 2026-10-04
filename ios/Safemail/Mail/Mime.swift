import Foundation

/// Builds the RFC 822 text Gmail's `messages.send` wants, and checks recipients.
enum Mime {
    /// Addresses separated by commas or semicolons, with line breaks removed.
    static func recipients(_ text: String) -> [String] {
        text.split(whereSeparator: { $0 == "," || $0 == ";" })
            .map { withoutLineBreaks(String($0)).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    /// At least one recipient, and each one is `address` or `Name <address>` with a plausible address.
    static func isValidRecipientList(_ text: String) -> Bool {
        let list = recipients(text)
        return !list.isEmpty && list.allSatisfy { bareAddress($0).wholeMatch(of: /[^@\s,;<>]+@[^@\s,;<>]+\.[^@\s,;<>]+/) != nil }
    }

    private static func withoutLineBreaks(_ value: String) -> String {
        value.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "")
    }

    /// Header values can't contain line breaks (they would let text inject extra headers), and non-ASCII
    /// text goes into an RFC 2047 encoded word.
    private static func headerValue(_ value: String) -> String {
        let clean = withoutLineBreaks(value).trimmingCharacters(in: .whitespaces)
        return clean.allSatisfy(\.isASCII) ? clean : "=?UTF-8?B?\(Data(clean.utf8).base64EncodedString())?="
    }

    static func raw(_ message: OutgoingMessage) -> String {
        var headers = [
            "To: \(recipients(message.to).map { headerValue($0) }.joined(separator: ", "))",
            "Subject: \(headerValue(message.subject))",
        ]
        if let inReplyTo = message.inReplyTo, !inReplyTo.isEmpty { headers.append("In-Reply-To: \(withoutLineBreaks(inReplyTo))") }
        if let references = message.references, !references.isEmpty { headers.append("References: \(withoutLineBreaks(references))") }
        headers += [
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=\"UTF-8\"",
            "Content-Transfer-Encoding: base64",
        ]
        let body = message.body.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\n", with: "\r\n")
        let encoded = Data(body.utf8).base64EncodedString(options: [.lineLength76Characters, .endLineWithCarriageReturn, .endLineWithLineFeed])
        return headers.joined(separator: "\r\n") + "\r\n\r\n" + encoded
    }
}
