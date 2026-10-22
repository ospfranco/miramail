package com.ospfranco.trustmail.mail

import kotlinx.coroutines.delay

/**
 * Debug-only stand-ins so the UI can be exercised without a Google account.
 * Launch the activity with the boolean extra `demo` (the iOS app does the same with a `-demo` argument):
 * `adb shell am start -n me.miramail/com.ospfranco.trustmail.MainActivity --ez demo true`
 */
object Demo {
    const val EXTRA = "demo"

    /** `--ez demo_signed_out true` starts signed out, to see the intro and the sign-in screen. */
    const val EXTRA_SIGNED_OUT = "demo_signed_out"

    /** `--ez demo_offline true` starts with a saved inbox and Gmail out of reach, to see the inbox snapshot. */
    const val EXTRA_OFFLINE = "demo_offline"

    private val welcome = MessageDetail(
        id = "demo-1",
        from = "Miramail Team <hello@example.com>",
        fromAddress = "hello@example.com",
        to = "you@example.com",
        subject = "Hello from the Miramail team",
        date = "Fri, 2 Oct 2026 09:00:00 +0000",
        body = "Hi there,\n\nRead how we scan every link: https://example.com/how-it-works\n\nMiramail checks every email before you act on it. When you open a message, it looks at who really sent it and scans each link it contains.\n\nA green mark next to a link means nothing suspicious was found. A yellow mark means something deserves a closer look, like a link that hides where it goes. A red mark means the link looks unsafe, and Miramail asks you to confirm before it opens.\n\nIf anything is wrong, it shows in bright colors at the top of the email, with what was found and what to do: a sender Gmail couldn't confirm, someone you have never exchanged email with, or a link that looks unsafe.\n\nIn your inbox, a sender that failed Gmail's checks shows its domain in red, and someone you have never exchanged email with gets a yellow mark, so you can spot them before you even open the email.\n\nTrustmail never opens the links themselves while scanning. It only checks the address, the domain and its certificate.\n\nIf an email ever looks wrong to you, trust that feeling: don't reply with personal details, and contact the sender another way.\n\nPhishing emails often copy the look of a bank, a delivery company or a colleague. They create urgency, ask you to sign in, or promise a refund, and the link behind the button goes somewhere else entirely.\n\nLook at where a link really goes, not just what it says. A link that reads like your bank's address can point to a look-alike domain registered last week.\n\nBe careful with attachments and with requests to pay, to change bank details or to share a code you received by text message. Real companies don't ask for those by email.\n\nWhen Miramail can't confirm who sent an email, it tells you so in plain words and explains what to watch out for.\n\nWe hope Miramail helps you read your email with confidence.\n\nThanks!",
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

    private fun summaries(gone: Set<String> = emptySet()) =
        all.withIndex().filter { it.value.id !in gone }.map { (index, m) ->
            MessageSummary(m.id, m.id, m.from, m.subject, m.body.take(80), unread = index == 1, authenticationResults = m.authenticationResults)
        }

    /** The inbox as a previous run would have saved it. */
    val savedInbox get() = InboxSnapshot("demo@example.com", summaries(), setOf("demo-2"))

    /** A mailbox that remembers what happens to it, so the UI can be exercised end to end. */
    class Mailbox(private val offline: Boolean = false) : MailService {
        private val gone = mutableSetOf<String>()
        val sent = mutableListOf<OutgoingMessage>()

        override suspend fun listInbox(maxResults: Int): List<MessageSummary> {
            if (offline) throw java.io.IOException("Unable to resolve host \"gmail.googleapis.com\": No address associated with hostname")
            return synchronized(gone) { summaries(gone) }
        }

        override suspend fun getMessage(id: String) =
            all.firstOrNull { it.id == id } ?: throw GmailException(404, "No such demo message")

        override suspend fun hasCorrespondence(address: String, excludingMessageId: String): Boolean {
            // Part of every scan: a pause here keeps the "checking" row on screen long enough for UI tests to see it.
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

        override suspend fun moveTargets() = listOf(MailLabel("work", "Work"), MailLabel("receipts", "Receipts"), MailLabel.Spam)

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

    fun services(signedOut: Boolean = false, offline: Boolean = false) = Services(DemoAuth(signedOut), Mailbox(offline))
}
