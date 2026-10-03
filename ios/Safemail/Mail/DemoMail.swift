#if DEBUG
import Foundation

/// Debug-only stand-ins so the UI can be exercised without a Google account.
/// Launch the app with the `-demo` argument to use them (the Android app does the same with a `demo` intent extra).
enum Demo {
    static var isEnabled: Bool { ProcessInfo.processInfo.arguments.contains("-demo") }

    /// Settings for demo runs start from the defaults every launch and never touch the real preferences.
    static func freshSettingsStore() -> UserDefaults {
        let suite = "safemail.demo"
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        return UserDefaults(suiteName: suite)!
    }

    private static let welcome = MessageDetail(
        id: "demo-1",
        from: "Safemail Team <hello@example.com>",
        fromAddress: "hello@example.com",
        to: "you@example.com",
        subject: "Welcome to Safemail",
        date: "Fri, 2 Oct 2026 09:00:00 +0000",
        body: "Hi there,\n\nRead how we scan every link: https://example.com/how-it-works\n\nThanks!",
        bodyType: .text,
        authenticationResults: ["mx.google.com; dkim=pass header.i=@example.com; spf=pass smtp.mailfrom=hello@example.com; dmarc=pass header.from=example.com"]
    )

    private static let phishing = MessageDetail(
        id: "demo-2",
        from: "PayPal Support <support@paypa1-secure.example.net>",
        fromAddress: "support@paypa1-secure.example.net",
        to: "you@example.com",
        subject: "Action required: verify your account",
        date: "Fri, 2 Oct 2026 08:15:00 +0000",
        body: """
        <h2>Your account is limited</h2>
        <p>Please <a href="http://login.paypa1-secure.example.net/verify">paypal.com/login</a> within 24 hours.</p>
        <p>Or use this shortcut: <a href="https://bit.ly/3xYzAbC">Fix my account</a></p>
        <p><a href="javascript:alert(1)">Unsubscribe</a></p>
        <p id="js-status">Scripts in this email are blocked.</p>
        <script>document.getElementById('js-status').textContent = 'Scripts in this email are running.'</script>
        """,
        bodyType: .html,
        authenticationResults: ["mx.google.com; dkim=fail header.i=@paypa1-secure.example.net; spf=fail smtp.mailfrom=support@paypa1-secure.example.net; dmarc=fail header.from=paypa1-secure.example.net"]
    )

    private static let all = [welcome, phishing]

    struct Mail: MailService {
        func listInbox(maxResults: Int) async throws -> [MessageSummary] {
            all.enumerated().map { index, m in
                MessageSummary(id: m.id, threadId: m.id, from: m.from, subject: m.subject, snippet: String(m.body.prefix(80)), unread: index == 1)
            }
        }

        func getMessage(id: String) async throws -> MessageDetail {
            guard let message = all.first(where: { $0.id == id }) else { throw GmailError(status: 404, body: "No such demo message") }
            return message
        }

        func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool {
            address == welcome.fromAddress
        }
    }

    struct Auth: AuthService {
        func restoreSession() async throws -> UserProfile? { UserProfile(email: "demo@example.com") }
        func signIn() async throws -> UserProfile? { UserProfile(email: "demo@example.com") }
        func signOut() async {}
    }
}
#endif
