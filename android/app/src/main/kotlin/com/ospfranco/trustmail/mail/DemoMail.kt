package com.ospfranco.trustmail.mail

import kotlinx.coroutines.delay

/**
 * Debug-only stand-ins so the UI can be exercised without a Google account.
 * Launch the activity with the boolean extra `demo` (the iOS app does the same with a `-demo` argument):
 * `adb shell am start -n com.ospfranco.trustmail/.MainActivity --ez demo true`
 */
object Demo {
    const val EXTRA = "demo"

    /** `--ez demo_signed_out true` starts signed out, to see the intro and the sign-in screen. */
    const val EXTRA_SIGNED_OUT = "demo_signed_out"

    private val welcome = MessageDetail(
        id = "demo-1",
        from = "Trustmail Team <hello@example.com>",
        fromAddress = "hello@example.com",
        to = "you@example.com",
        subject = "Hello from the Trustmail team",
        date = "Fri, 2 Oct 2026 09:00:00 +0000",
        body = "Hi there,\n\nRead how we scan every link: https://example.com/how-it-works\n\nThanks!",
        bodyType = BodyType.Text,
        authenticationResults = listOf("mx.google.com; dkim=pass header.i=@example.com; spf=pass smtp.mailfrom=hello@example.com; dmarc=pass header.from=example.com"),
        threadId = "demo-thread-1",
        messageId = "<welcome@example.com>",
    )

    private val phishing = MessageDetail(
        id = "demo-2",
        from = "PayPal Support <support@paypa1-secure.example.net>",
        fromAddress = "support@paypa1-secure.example.net",
        to = "you@example.com",
        subject = "Action required: verify your account",
        date = "Fri, 2 Oct 2026 08:15:00 +0000",
        body = """
            <h2>Your account is limited</h2>
            <p>Please <a href="http://login.paypa1-secure.example.net/verify">paypal.com/login</a> within 24 hours.</p>
            <p>Or use this shortcut: <a href="https://bit.ly/3xYzAbC">Fix my account</a></p>
            <p><a href="javascript:alert(1)">Unsubscribe</a></p>
            <p id="js-status">Scripts in this email are blocked.</p>
            <script>document.getElementById('js-status').textContent = 'Scripts in this email are running.'</script>
        """.trimIndent(),
        bodyType = BodyType.Html,
        authenticationResults = listOf("mx.google.com; dkim=fail header.i=@paypa1-secure.example.net; spf=fail smtp.mailfrom=support@paypa1-secure.example.net; dmarc=fail header.from=paypa1-secure.example.net"),
        threadId = "demo-thread-2",
        messageId = "<verify@paypa1-secure.example.net>",
    )

    private val all = listOf(welcome, phishing)

    /** A mailbox that remembers what happens to it, so the UI can be exercised end to end. */
    class Mailbox : MailService {
        private val gone = mutableSetOf<String>()
        val sent = mutableListOf<OutgoingMessage>()

        override suspend fun listInbox(maxResults: Int) = synchronized(gone) {
            all.withIndex().filter { it.value.id !in gone }.map { (index, m) ->
                MessageSummary(m.id, m.id, m.from, m.subject, m.body.take(80), unread = index == 1, authenticationResults = m.authenticationResults)
            }
        }

        override suspend fun getMessage(id: String) =
            all.firstOrNull { it.id == id } ?: throw GmailException(404, "No such demo message")

        override suspend fun hasCorrespondence(address: String, excludingMessageId: String): Boolean {
            // Part of every scan: a pause here keeps the "scanning" tab on screen long enough for UI tests to see it.
            delay(1500)
            return address == welcome.fromAddress
        }

        override suspend fun trash(id: String) {
            synchronized(gone) { gone.add(id) }
        }

        override suspend fun archive(id: String) {
            synchronized(gone) { gone.add(id) }
        }

        override suspend fun move(id: String, label: MailLabel) {
            synchronized(gone) { gone.add(id) }
        }

        override suspend fun moveTargets() = listOf(MailLabel("work", "Work"), MailLabel("receipts", "Receipts"), MailLabel("SPAM", "Spam"))

        override suspend fun send(message: OutgoingMessage) {
            synchronized(sent) { sent.add(message) }
        }
    }

    private class DemoAuth(private val signedOut: Boolean) : AuthService {
        private val user = UserProfile("demo@example.com")
        override suspend fun restoreSession() = if (signedOut) null else user
        override suspend fun signIn() = user
        override suspend fun signOut() {}
    }

    fun services(signedOut: Boolean = false) = Services(DemoAuth(signedOut), Mailbox())
}
