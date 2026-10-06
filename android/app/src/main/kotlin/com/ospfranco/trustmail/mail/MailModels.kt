package com.ospfranco.trustmail.mail

import kotlinx.serialization.Serializable

/** The name in `Name <address>` (without quotes), or the address when there is no name. */
fun senderName(header: String): String {
    val name = Regex("""^\s*"?([^"<]*?)"?\s*<""").find(header)?.groupValues?.get(1).orEmpty()
    return name.ifEmpty { bareAddress(header) }
}

/** The circle shown next to a sender: their initial on one of a few colors, the same color for the same address. */
data class SenderAvatar(val initial: String, val hexColor: String) {
    companion object {
        val palette = listOf("1E88E5", "43A047", "F4511E", "8E24AA", "00897B", "6D4C41", "3949AB", "D81B60")

        fun of(header: String): SenderAvatar {
            val initial = senderName(header).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            val sum = bareAddress(header).codePoints().sum()
            return SenderAvatar(initial, palette[sum % palette.size])
        }
    }
}

@Serializable
data class MessageSummary(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val unread: Boolean,
    /** Gmail's own SPF/DKIM/DMARC verdicts, so the inbox can mark a sender that failed them. */
    val authenticationResults: List<String> = emptyList(),
)

enum class BodyType { Html, Text }

data class MessageDetail(
    val id: String,
    val from: String,
    /** Bare address from the From header, e.g. "noreply@github.com". */
    val fromAddress: String,
    val to: String,
    val subject: String,
    val date: String,
    val body: String,
    val bodyType: BodyType,
    /** Gmail's own SPF/DKIM/DMARC verdicts. Other servers' headers are dropped since senders can forge them. */
    val authenticationResults: List<String>,
    // What a reply needs to stay in the same conversation.
    val threadId: String = "",
    /** The `Message-ID` header, with its angle brackets. */
    val messageId: String = "",
    val references: String = "",
    val replyTo: String = "",
)

/** A Gmail label. Gmail has labels where other mail apps have folders. */
data class MailLabel(val id: String, val name: String)

/** A message the user is writing. */
data class OutgoingMessage(
    val to: String,
    val subject: String,
    val body: String,
    val threadId: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
)

/** The address inside `Name <address>`, or the text itself, lowercased. */
fun bareAddress(header: String): String =
    (Regex("<([^>]+)>").find(header)?.groupValues?.get(1) ?: header).trim().lowercase()

/** The prefilled reply: to the Reply-To address (else the sender), "Re:" subject, same conversation. */
fun MessageDetail.makeReply(): OutgoingMessage {
    val target = if (replyTo.isBlank()) fromAddress else bareAddress(replyTo)
    val original = if (subject == "(no subject)") "" else subject
    val reSubject = if (Regex("^re:", RegexOption.IGNORE_CASE).containsMatchIn(original)) original else "Re: $original"
    val chain = listOf(references, messageId).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    return OutgoingMessage(
        to = target,
        subject = reSubject,
        body = "",
        threadId = threadId.ifEmpty { null },
        inReplyTo = messageId.ifEmpty { null },
        references = chain.ifEmpty { null },
    )
}

data class UserProfile(val email: String)

/** Where mail comes from. The real implementation talks to Gmail; debug builds can swap in demo data. */
interface MailService {
    suspend fun listInbox(maxResults: Int = 20): List<MessageSummary>

    suspend fun getMessage(id: String): MessageDetail

    /** Whether the user has any other mail from or to [address] (spam and trash excluded). */
    suspend fun hasCorrespondence(address: String, excludingMessageId: String): Boolean

    /** Moves the message to the trash (it can still be recovered there). */
    suspend fun trash(id: String)

    /** Takes the message out of the inbox, keeping it in All Mail. */
    suspend fun archive(id: String)

    /** Files the message under [label] and takes it out of the inbox. */
    suspend fun move(id: String, label: MailLabel)

    /** Where a message can be moved: the user's own labels, then Spam. */
    suspend fun moveTargets(): List<MailLabel>

    suspend fun send(message: OutgoingMessage)
}

interface AuthService {
    /** Restores a previous session without UI. Returns null if the user never signed in. */
    suspend fun restoreSession(): UserProfile?

    /** Shows the sign-in UI. Returns null if the user cancelled. */
    suspend fun signIn(): UserProfile?

    suspend fun signOut()
}

data class Services(val auth: AuthService, val mail: MailService)
