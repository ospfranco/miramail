package com.ospfranco.safemail.mail

data class MessageSummary(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val snippet: String,
    val unread: Boolean,
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
)

data class UserProfile(val email: String)

/** Where mail comes from. The real implementation talks to Gmail; debug builds can swap in demo data. */
interface MailService {
    suspend fun listInbox(maxResults: Int = 20): List<MessageSummary>

    suspend fun getMessage(id: String): MessageDetail

    /** Whether the user has any other mail from or to [address] (spam and trash excluded). */
    suspend fun hasCorrespondence(address: String, excludingMessageId: String): Boolean
}

interface AuthService {
    /** Restores a previous session without UI. Returns null if the user never signed in. */
    suspend fun restoreSession(): UserProfile?

    /** Shows the sign-in UI. Returns null if the user cancelled. */
    suspend fun signIn(): UserProfile?

    suspend fun signOut()
}

data class Services(val auth: AuthService, val mail: MailService)
