#if DEBUG
import Foundation

/// Debug-only stand-ins so the UI can be exercised without a Google account.
/// Launch the app with the `-demo` argument to use them (the Android app does the same with a `demo` intent extra).
enum Demo {
    static var isEnabled: Bool { ProcessInfo.processInfo.arguments.contains("-demo") }

    /// Settings for demo runs start from the defaults every launch and never touch the real preferences.
    static func freshSettingsStore() -> UserDefaults {
        let suite = "trustmail.demo"
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        return UserDefaults(suiteName: suite)!
    }

    private static let welcome = MessageDetail(
        id: "demo-1",
        from: "Trustmail Team <hello@example.com>",
        fromAddress: "hello@example.com",
        to: "you@example.com",
        subject: "Hello from the Trustmail team",
        date: "Fri, 2 Oct 2026 09:00:00 +0000",
        body: "Hi there,\n\nRead how we scan every link: https://example.com/how-it-works\n\nTrustmail checks every email before you act on it. When you open a message, it looks at who really sent it and scans each link it contains.\n\nA green mark next to a link means nothing suspicious was found. A yellow mark means something deserves a closer look, like a link that hides where it goes. A red mark means the link looks unsafe, and Trustmail asks you to confirm before it opens.\n\nIf anything is wrong, it shows in bright colors at the top of the email, with what was found and what to do: a sender Gmail couldn't confirm, someone you have never exchanged email with, or a link that looks unsafe.\n\nIn your inbox, a sender that failed Gmail's checks shows its domain in red, and someone you have never exchanged email with gets a yellow mark, so you can spot them before you even open the email.\n\nTrustmail never opens the links themselves while scanning. It only checks the address, the domain and its certificate.\n\nIf an email ever looks wrong to you, trust that feeling: don't reply with personal details, and contact the sender another way.\n\nPhishing emails often copy the look of a bank, a delivery company or a colleague. They create urgency, ask you to sign in, or promise a refund, and the link behind the button goes somewhere else entirely.\n\nLook at where a link really goes, not just what it says. A link that reads like your bank's address can point to a look-alike domain registered last week.\n\nBe careful with attachments and with requests to pay, to change bank details or to share a code you received by text message. Real companies don't ask for those by email.\n\nWhen Trustmail can't confirm who sent an email, it tells you so in plain words and explains what to watch out for.\n\nWe hope Trustmail helps you read your email with confidence.\n\nThanks!",
        bodyType: .text,
        authenticationResults: ["mx.google.com; dkim=pass header.i=@example.com; spf=pass smtp.mailfrom=hello@example.com; dmarc=pass header.from=example.com"],
        threadId: "demo-thread-1",
        messageId: "<welcome@example.com>"
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
        authenticationResults: ["mx.google.com; dkim=fail header.i=@paypa1-secure.example.net; spf=fail smtp.mailfrom=support@paypa1-secure.example.net; dmarc=fail header.from=paypa1-secure.example.net"],
        threadId: "demo-thread-2",
        messageId: "<verify@paypa1-secure.example.net>"
    )

    private static let all = [welcome, phishing]

    /// `-demo-offline` starts with a saved inbox and Gmail out of reach, to see the inbox snapshot.
    static var isOffline: Bool { ProcessInfo.processInfo.arguments.contains("-demo-offline") }

    private static func summaries(excluding gone: Set<String> = []) -> [MessageSummary] {
        all.enumerated().filter { !gone.contains($0.element.id) }.map { index, m in
            MessageSummary(
                id: m.id, threadId: m.id, from: m.from, subject: m.subject, snippet: String(m.body.prefix(80)), unread: index == 1,
                authenticationResults: m.authenticationResults
            )
        }
    }

    /// The inbox as a previous run would have saved it.
    static var savedInbox: InboxSnapshot {
        InboxSnapshot(account: "demo@example.com", messages: summaries(), firstMessages: ["demo-2"])
    }

    /// A mailbox that remembers what happens to it, so the UI can be exercised end to end.
    actor Mail: MailService {
        private var gone: Set<String> = []
        private(set) var sent: [OutgoingMessage] = []

        func listInbox(maxResults: Int) async throws -> [MessageSummary] {
            if Demo.isOffline { throw URLError(.notConnectedToInternet) }
            return Demo.summaries(excluding: gone)
        }

        func getMessage(id: String) async throws -> MessageDetail {
            guard let message = all.first(where: { $0.id == id }) else { throw GmailError(status: 404, body: "No such demo message") }
            return message
        }

        func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool {
            // Part of every scan: a pause here keeps the "checking" row on screen long enough for UI tests to see it.
            try await Task.sleep(for: .milliseconds(1500))
            return address == welcome.fromAddress
        }

        func trash(id: String) async throws { gone.insert(id) }
        func archive(id: String) async throws { gone.insert(id) }
        func move(id: String, to label: MailLabel) async throws { gone.insert(id) }

        func moveTargets() async throws -> [MailLabel] {
            [MailLabel(id: "work", name: "Work"), MailLabel(id: "receipts", name: "Receipts"), .spam]
        }

        func send(_ message: OutgoingMessage) async throws { sent.append(message) }
    }

    /// `-demo-signed-out` starts signed out, to see the intro and the sign-in screen.
    static var startsSignedOut: Bool { ProcessInfo.processInfo.arguments.contains("-demo-signed-out") }

    struct Auth: AuthService {
        func restoreSession() async throws -> UserProfile? { Demo.startsSignedOut ? nil : UserProfile(email: "demo@example.com") }
        func signIn() async throws -> UserProfile? { UserProfile(email: "demo@example.com") }
        func signOut() async {}
    }
}
#endif
