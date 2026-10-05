package com.ospfranco.trustmail.mail

import java.util.Base64

/** Builds the RFC 822 text Gmail's `messages.send` wants, and checks recipients. */
object Mime {
    private val plausibleAddress = Regex("""[^@\s,;<>]+@[^@\s,;<>]+\.[^@\s,;<>]+""")

    /** Addresses separated by commas or semicolons, with line breaks removed. */
    fun recipients(text: String): List<String> =
        text.split(',', ';').map { withoutLineBreaks(it).trim() }.filter { it.isNotEmpty() }

    /** At least one recipient, and each one is `address` or `Name <address>` with a plausible address. */
    fun isValidRecipientList(text: String): Boolean {
        val list = recipients(text)
        return list.isNotEmpty() && list.all { plausibleAddress.matches(bareAddress(it)) }
    }

    private fun withoutLineBreaks(value: String) = value.replace("\r", "").replace("\n", "")

    /**
     * Header values can't contain line breaks (they would let text inject extra headers), and non-ASCII
     * text goes into an RFC 2047 encoded word.
     */
    private fun headerValue(value: String): String {
        val clean = withoutLineBreaks(value).trim()
        return if (clean.all { it.code < 128 }) clean else "=?UTF-8?B?${Base64.getEncoder().encodeToString(clean.toByteArray())}?="
    }

    fun raw(message: OutgoingMessage): String {
        val headers = mutableListOf(
            "To: ${recipients(message.to).joinToString(", ") { headerValue(it) }}",
            "Subject: ${headerValue(message.subject)}",
        )
        message.inReplyTo?.takeIf { it.isNotEmpty() }?.let { headers.add("In-Reply-To: ${withoutLineBreaks(it)}") }
        message.references?.takeIf { it.isNotEmpty() }?.let { headers.add("References: ${withoutLineBreaks(it)}") }
        headers += listOf(
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=\"UTF-8\"",
            "Content-Transfer-Encoding: base64",
        )
        val body = message.body.replace("\r\n", "\n").replace("\n", "\r\n")
        val encoded = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray())
        return headers.joinToString("\r\n") + "\r\n\r\n" + encoded
    }
}
