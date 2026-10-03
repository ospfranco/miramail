package com.ospfranco.safemail.mail

import com.ospfranco.safemail.security.base64ToBytes
import com.ospfranco.safemail.security.utf8Decode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class GmailException(val status: Int, val body: String) : Exception("Gmail API $status: $body")

/** Talks to the Gmail REST API. [accessToken] supplies OAuth tokens; pass `true` to discard a rejected one. */
class GmailClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = BASE_URL,
    private val accessToken: suspend (forceRefresh: Boolean) -> String,
) : MailService {
    companion object {
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // region Wire format

    @Serializable
    private class MessageRef(val id: String)

    @Serializable
    private class MessageList(val messages: List<MessageRef>? = null)

    @Serializable
    private class Header(val name: String, val value: String)

    @Serializable
    private class Body(val size: Int? = null, val data: String? = null)

    @Serializable
    private class MessagePart(
        val mimeType: String? = null,
        val filename: String? = null,
        val headers: List<Header>? = null,
        val body: Body? = null,
        val parts: List<MessagePart>? = null,
    ) {
        fun header(name: String): String = headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value ?: ""
    }

    @Serializable
    private class RawMessage(
        val id: String,
        val threadId: String,
        val snippet: String? = null,
        val labelIds: List<String>? = null,
        val payload: MessagePart,
    )

    // endregion

    private suspend fun fetchText(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            path.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment)
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

        suspend fun request(token: String): Pair<Int, String> = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            client.newCall(request).execute().use { it.code to it.body.string() }
        }

        var (status, body) = request(accessToken(false))
        if (status == 401) request(accessToken(true)).let { status = it.first; body = it.second }
        if (status !in 200..299) throw GmailException(status, body)
        return body
    }

    private suspend inline fun <reified T> fetch(path: String, query: List<Pair<String, String>> = emptyList()): T =
        json.decodeFromString(fetchText(path, query))

    override suspend fun listInbox(maxResults: Int): List<MessageSummary> {
        val list = fetch<MessageList>("messages", listOf("labelIds" to "INBOX", "maxResults" to maxResults.toString()))
        val full = coroutineScope {
            list.messages.orEmpty().map { ref ->
                async {
                    fetch<RawMessage>(
                        "messages/${ref.id}",
                        listOf("format" to "metadata", "metadataHeaders" to "From", "metadataHeaders" to "Subject"),
                    )
                }
            }.awaitAll()
        }
        return full.map { message ->
            MessageSummary(
                id = message.id,
                threadId = message.threadId,
                from = message.payload.header("From"),
                subject = message.payload.header("Subject").ifEmpty { "(no subject)" },
                snippet = message.snippet.orEmpty(),
                unread = message.labelIds?.contains("UNREAD") ?: false,
            )
        }
    }

    override suspend fun getMessage(id: String): MessageDetail {
        val message = fetch<RawMessage>("messages/$id", listOf("format" to "full"))
        val html = findPart(message.payload, "text/html")
        val text = if (html == null) findPart(message.payload, "text/plain") else null
        val part = html ?: text

        val from = message.payload.header("From")
        val address = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from

        return MessageDetail(
            id = message.id,
            from = from,
            fromAddress = address.trim().lowercase(),
            to = message.payload.header("To"),
            subject = message.payload.header("Subject").ifEmpty { "(no subject)" },
            date = message.payload.header("Date"),
            // Non-UTF-8 charsets (e.g. ISO-8859-1) will render garbled.
            body = part?.body?.data?.let { utf8Decode(base64ToBytes(it)) } ?: message.snippet.orEmpty(),
            bodyType = if (html != null) BodyType.Html else BodyType.Text,
            authenticationResults = message.payload.headers.orEmpty()
                .filter { it.name.equals("authentication-results", ignoreCase = true) }
                .map { it.value }
                .filter { Regex("""^\s*mx\.google\.com\s*;""", RegexOption.IGNORE_CASE).containsMatchIn(it) },
        )
    }

    override suspend fun hasCorrespondence(address: String, excludingMessageId: String): Boolean {
        val list = fetch<MessageList>("messages", listOf("q" to "{from:\"$address\" to:\"$address\"}", "maxResults" to "2"))
        return list.messages.orEmpty().any { it.id != excludingMessageId }
    }

    /** The signed-in account's own address. */
    suspend fun profileEmail(): String {
        @Serializable
        class Profile(val emailAddress: String)
        return fetch<Profile>("profile").emailAddress
    }

    /** Depth-first search for the first inline (non-attachment) part of the given MIME type. */
    private fun findPart(part: MessagePart, mimeType: String): MessagePart? {
        if (part.mimeType == mimeType && part.filename.isNullOrEmpty() && part.body?.data != null) return part
        for (child in part.parts.orEmpty()) findPart(child, mimeType)?.let { return it }
        return null
    }
}
