import Foundation

struct MessageSummary: Sendable, Equatable, Hashable, Identifiable {
    let id: String
    let threadId: String
    let from: String
    let subject: String
    let snippet: String
    let unread: Bool
}

enum BodyType: Sendable {
    case html
    case text
}

struct MessageDetail: Sendable, Equatable {
    let id: String
    let from: String
    /// Bare address from the From header, e.g. "noreply@github.com".
    let fromAddress: String
    let to: String
    let subject: String
    let date: String
    let body: String
    let bodyType: BodyType
    /// Gmail's own SPF/DKIM/DMARC verdicts. Other servers' headers are dropped since senders can forge them.
    let authenticationResults: [String]
    // What a reply needs to stay in the same conversation.
    var threadId: String = ""
    /// The `Message-ID` header, with its angle brackets.
    var messageId: String = ""
    var references: String = ""
    var replyTo: String = ""
}

/// A Gmail label. Gmail has labels where other mail apps have folders.
struct MailLabel: Sendable, Equatable, Hashable, Identifiable {
    let id: String
    let name: String
}

/// A message the user is writing.
struct OutgoingMessage: Sendable, Equatable, Hashable {
    var to: String
    var subject: String
    var body: String
    var threadId: String? = nil
    var inReplyTo: String? = nil
    var references: String? = nil
}

/// The address inside `Name <address>`, or the text itself, lowercased.
func bareAddress(_ header: String) -> String {
    let address = header.firstMatch(of: /<([^>]+)>/).map { String($0.1) } ?? header
    return address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
}

extension MessageDetail {
    /// The prefilled reply: to the Reply-To address (else the sender), "Re:" subject, same conversation.
    func makeReply() -> OutgoingMessage {
        let target = replyTo.trimmingCharacters(in: .whitespaces).isEmpty ? fromAddress : bareAddress(replyTo)
        let original = subject == "(no subject)" ? "" : subject
        let reSubject = original.range(of: #"^re:"#, options: [.regularExpression, .caseInsensitive]) != nil ? original : "Re: \(original)"
        let chain = [references, messageId].map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: " ")
        return OutgoingMessage(
            to: target,
            subject: reSubject,
            body: "",
            threadId: threadId.isEmpty ? nil : threadId,
            inReplyTo: messageId.isEmpty ? nil : messageId,
            references: chain.isEmpty ? nil : chain
        )
    }
}

struct UserProfile: Sendable, Equatable {
    let email: String
}

/// Where mail comes from. The real implementation talks to Gmail; debug builds can swap in demo data.
protocol MailService: Sendable {
    func listInbox(maxResults: Int) async throws -> [MessageSummary]
    func getMessage(id: String) async throws -> MessageDetail
    /// Whether the user has any other mail from or to `address` (spam and trash excluded).
    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool
    /// Moves the message to the trash (it can still be recovered there).
    func trash(id: String) async throws
    /// Takes the message out of the inbox, keeping it in All Mail.
    func archive(id: String) async throws
    /// Files the message under `label` and takes it out of the inbox.
    func move(id: String, to label: MailLabel) async throws
    /// Where a message can be moved: the user's own labels, then Spam.
    func moveTargets() async throws -> [MailLabel]
    func send(_ message: OutgoingMessage) async throws
}

protocol AuthService: Sendable {
    /// Restores a previous session without UI. Returns nil if the user never signed in.
    func restoreSession() async throws -> UserProfile?
    /// Shows the sign-in UI. Returns nil if the user cancelled.
    func signIn() async throws -> UserProfile?
    func signOut() async
}
