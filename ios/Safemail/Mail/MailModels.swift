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
}

protocol AuthService: Sendable {
    /// Restores a previous session without UI. Returns nil if the user never signed in.
    func restoreSession() async throws -> UserProfile?
    /// Shows the sign-in UI. Returns nil if the user cancelled.
    func signIn() async throws -> UserProfile?
    func signOut() async
}
