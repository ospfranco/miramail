import Foundation

struct GmailError: Error, LocalizedError {
    let status: Int
    let body: String

    var errorDescription: String? { "Gmail API \(status): \(body)" }
}

/// Talks to the Gmail REST API. `accessToken(forceRefresh)` supplies OAuth tokens.
final class GmailClient: MailService {
    typealias TokenProvider = @Sendable (_ forceRefresh: Bool) async throws -> String

    private static let base = "https://gmail.googleapis.com/gmail/v1/users/me"

    private let accessToken: TokenProvider
    private let session: URLSession

    init(session: URLSession = .shared, accessToken: @escaping TokenProvider) {
        self.session = session
        self.accessToken = accessToken
    }

    private func fetch<T: Decodable>(_ path: String, method: String = "GET", body: (any Encodable & Sendable)? = nil, as type: T.Type = T.self) async throws -> T {
        let payload = try body.map { try JSONEncoder().encode($0) }
        func request(token: String) async throws -> (Data, HTTPURLResponse) {
            var request = URLRequest(url: URL(string: Self.base + path)!)
            request.httpMethod = method
            if let payload {
                request.httpBody = payload
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await session.data(for: request)
            return (data, response as! HTTPURLResponse)
        }

        var (data, response) = try await request(token: try await accessToken(false))
        if response.statusCode == 401 { (data, response) = try await request(token: try await accessToken(true)) }
        guard (200..<300).contains(response.statusCode) else {
            throw GmailError(status: response.statusCode, body: String(decoding: data, as: UTF8.self))
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    // MARK: - Wire format

    /// Responses we only need to succeed.
    private struct Ignored: Decodable {}

    private struct ModifyBody: Encodable, Sendable {
        var addLabelIds: [String]?
        var removeLabelIds: [String]?
    }

    private struct SendBody: Encodable, Sendable {
        let raw: String
        var threadId: String?
    }

    private struct LabelList: Decodable {
        struct Item: Decodable {
            let id: String
            let name: String
            let type: String?
        }
        let labels: [Item]?
    }

    private struct MessageList: Decodable {
        struct Item: Decodable { let id: String }
        let messages: [Item]?
    }

    private struct MessagePart: Decodable {
        struct Header: Decodable {
            let name: String
            let value: String
        }
        struct Body: Decodable {
            let size: Int?
            let data: String?
        }
        let mimeType: String?
        let filename: String?
        let headers: [Header]?
        let body: Body?
        let parts: [MessagePart]?

        func header(_ name: String) -> String {
            headers?.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }?.value ?? ""
        }

        /// Gmail's own Authentication-Results. Other servers' headers are dropped since senders can forge them.
        var googleAuthenticationResults: [String] {
            (headers ?? [])
                .filter { $0.name.lowercased() == "authentication-results" }
                .map(\.value)
                .filter { $0.firstMatch(of: /^\s*mx\.google\.com\s*;/.ignoresCase()) != nil }
        }
    }

    private struct RawMessage: Decodable {
        let id: String
        let threadId: String
        let snippet: String?
        let labelIds: [String]?
        let payload: MessagePart
    }

    // MARK: - MailService

    func listInbox(maxResults: Int = 20) async throws -> [MessageSummary] {
        let list: MessageList = try await fetch("/messages?labelIds=INBOX&maxResults=\(maxResults)")
        let ids = (list.messages ?? []).map(\.id)

        let full = try await withThrowingTaskGroup(of: (Int, RawMessage).self) { group in
            for (index, id) in ids.enumerated() {
                group.addTask {
                    (index, try await self.fetch("/messages/\(id)?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Authentication-Results"))
                }
            }
            var results: [(Int, RawMessage)] = []
            for try await item in group { results.append(item) }
            return results.sorted { $0.0 < $1.0 }.map(\.1)
        }

        return full.map { message in
            let subject = message.payload.header("Subject")
            return MessageSummary(
                id: message.id,
                threadId: message.threadId,
                from: message.payload.header("From"),
                subject: subject.isEmpty ? "(no subject)" : subject,
                snippet: message.snippet ?? "",
                unread: message.labelIds?.contains("UNREAD") ?? false,
                authenticationResults: message.payload.googleAuthenticationResults
            )
        }
    }

    func getMessage(id: String) async throws -> MessageDetail {
        let message: RawMessage = try await fetch("/messages/\(id)?format=full")
        let html = Self.findPart(message.payload, mimeType: "text/html")
        let text = html == nil ? Self.findPart(message.payload, mimeType: "text/plain") : nil
        let part = html ?? text

        let from = message.payload.header("From")
        let address = from.firstMatch(of: /<([^>]+)>/).map { String($0.1) } ?? from
        let subject = message.payload.header("Subject")

        return MessageDetail(
            id: message.id,
            from: from,
            fromAddress: address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
            to: message.payload.header("To"),
            subject: subject.isEmpty ? "(no subject)" : subject,
            date: message.payload.header("Date"),
            // Non-UTF-8 charsets (e.g. ISO-8859-1) will render garbled.
            body: part?.body?.data.map { utf8Decode(base64ToBytes($0)) } ?? message.snippet ?? "",
            bodyType: html != nil ? .html : .text,
            authenticationResults: message.payload.googleAuthenticationResults,
            threadId: message.threadId,
            messageId: message.payload.header("Message-ID"),
            references: message.payload.header("References"),
            replyTo: message.payload.header("Reply-To")
        )
    }

    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool {
        // Trash counts (people delete what they've read), Spam doesn't.
        let query = encodeURIComponent("{from:\"\(address)\" to:\"\(address)\"} -in:spam")
        let list: MessageList = try await fetch("/messages?q=\(query)&maxResults=2&includeSpamTrash=true")
        return (list.messages ?? []).contains { $0.id != messageId }
    }

    func trash(id: String) async throws {
        let _: Ignored = try await fetch("/messages/\(id)/trash", method: "POST")
    }

    func archive(id: String) async throws {
        let _: Ignored = try await fetch("/messages/\(id)/modify", method: "POST", body: ModifyBody(removeLabelIds: ["INBOX"]))
    }

    func move(id: String, to label: MailLabel) async throws {
        let _: Ignored = try await fetch("/messages/\(id)/modify", method: "POST", body: ModifyBody(addLabelIds: [label.id], removeLabelIds: ["INBOX"]))
    }

    func moveTargets() async throws -> [MailLabel] {
        let list: LabelList = try await fetch("/labels")
        let own = (list.labels ?? [])
            .filter { $0.type == "user" }
            .map { MailLabel(id: $0.id, name: $0.name) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        return own + [.spam]
    }

    func send(_ message: OutgoingMessage) async throws {
        let raw = base64UrlEncode(Array(Mime.raw(message).utf8))
        let _: Ignored = try await fetch("/messages/send", method: "POST", body: SendBody(raw: raw, threadId: message.threadId))
    }

    /// Depth-first search for the first inline (non-attachment) part of the given MIME type.
    private static func findPart(_ part: MessagePart, mimeType: String) -> MessagePart? {
        if part.mimeType == mimeType, (part.filename ?? "").isEmpty, part.body?.data != nil { return part }
        for child in part.parts ?? [] {
            if let found = findPart(child, mimeType: mimeType) { return found }
        }
        return nil
    }
}
