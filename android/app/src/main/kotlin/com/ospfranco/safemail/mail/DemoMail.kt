package com.ospfranco.safemail.mail

/**
 * Debug-only stand-ins so the UI can be exercised without a Google account.
 * Launch the activity with the boolean extra `demo` (the iOS app does the same with a `-demo` argument):
 * `adb shell am start -n com.ospfranco.safemail/.MainActivity --ez demo true`
 */
object Demo {
    const val EXTRA = "demo"

    private val welcome = MessageDetail(
        id = "demo-1",
        from = "Safemail Team <hello@example.com>",
        fromAddress = "hello@example.com",
        to = "you@example.com",
        subject = "Welcome to Safemail",
        date = "Fri, 2 Oct 2026 09:00:00 +0000",
        body = "Hi there,\n\nRead how we scan every link: https://example.com/how-it-works\n\nThanks!",
        bodyType = BodyType.Text,
        authenticationResults = listOf("mx.google.com; dkim=pass header.i=@example.com; spf=pass smtp.mailfrom=hello@example.com; dmarc=pass header.from=example.com"),
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
    )

    private val all = listOf(welcome, phishing)

    val mail = object : MailService {
        override suspend fun listInbox(maxResults: Int) = all.mapIndexed { index, m ->
            MessageSummary(m.id, m.id, m.from, m.subject, m.body.take(80), unread = index == 1)
        }

        override suspend fun getMessage(id: String) =
            all.firstOrNull { it.id == id } ?: throw GmailException(404, "No such demo message")

        override suspend fun hasCorrespondence(address: String, excludingMessageId: String) = address == welcome.fromAddress
    }

    val auth = object : AuthService {
        private val user = UserProfile("demo@example.com")
        override suspend fun restoreSession() = user
        override suspend fun signIn() = user
        override suspend fun signOut() {}
    }

    val services = Services(auth, mail)
}
