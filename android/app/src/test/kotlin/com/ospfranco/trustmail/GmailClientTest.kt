package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.BodyType
import com.ospfranco.trustmail.mail.GmailClient
import com.ospfranco.trustmail.mail.GmailException
import com.ospfranco.trustmail.mail.MessageSummary
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class GmailClientTest {
    private lateinit var server: MockWebServer
    private val tokenRequests = CopyOnWriteArrayList<Boolean>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = GmailClient(baseUrl = server.url("/gmail/v1/users/me").toString().trimEnd('/')) { forceRefresh ->
        tokenRequests.add(forceRefresh)
        if (forceRefresh) "fresh-token" else "old-token"
    }

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    private fun b64url(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test
    fun listsInboxInOrderWithMetadata() = runTest {
        serve { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/gmail/v1/users/me/messages?") && "labelIds=INBOX" in path && "maxResults=20" in path ->
                    MockResponse().setBody("""{"messages":[{"id":"a"},{"id":"b"}]}""")
                else -> {
                    val id = if ("/messages/a?" in path) "a" else "b"
                    val labels = if (id == "a") ""","labelIds":["INBOX","UNREAD"]""" else ""
                    val subject = if (id == "a") "" else """{"name":"subject","value":"Hello"},"""
                    // Only Gmail's own Authentication-Results count; a sender can add their own.
                    val auth = if (id == "a") {
                        """{"name":"Authentication-Results","value":"mx.google.com; dmarc=fail"},{"name":"Authentication-Results","value":"evil.example; dmarc=pass"},"""
                    } else {
                        ""
                    }
                    // The first message answers slower, to prove results keep inbox order.
                    MockResponse()
                        .setBody("""{"id":"$id","threadId":"t$id","snippet":"snip $id"$labels,"payload":{"headers":[$subject$auth{"name":"From","value":"Ann <ann@x.com>"}]}}""")
                        .apply { if (id == "a") setBodyDelay(200, TimeUnit.MILLISECONDS) }
                }
            }
        }
        assertEquals(
            listOf(
                MessageSummary("a", "ta", "Ann <ann@x.com>", "(no subject)", "snip a", true, listOf("mx.google.com; dmarc=fail")),
                MessageSummary("b", "tb", "Ann <ann@x.com>", "Hello", "snip b", false),
            ),
            client().listInbox(20),
        )
    }

    @Test
    fun emptyInbox() = runTest {
        serve { MockResponse().setBody("{}") }
        assertTrue(client().listInbox(20).isEmpty())
    }

    @Test
    fun sendsTheBearerTokenAndRequestsOnlyHeaderMetadata() = runTest {
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        serve { request ->
            seen.add(request)
            if (request.path.orEmpty().contains("/messages?")) MockResponse().setBody("""{"messages":[{"id":"a"}]}""")
            else MockResponse().setBody("""{"id":"a","threadId":"t","payload":{}}""")
        }
        client().listInbox(5)
        assertTrue(seen.all { it.getHeader("Authorization") == "Bearer old-token" })
        val detail = seen.first { "/messages/a" in it.path.orEmpty() }.requestUrl!!
        assertEquals("metadata", detail.queryParameter("format"))
        assertEquals(listOf("From", "Subject", "Authentication-Results"), detail.queryParameterValues("metadataHeaders"))
    }

    @Test
    fun parsesMultipartMessagePreferringHtmlAndSkippingAttachments() = runTest {
        val html = "<p>✓ Hi</p>"
        serve {
            MockResponse().setBody(
                """
                {"id":"m1","threadId":"t1","snippet":"snippet","payload":{"mimeType":"multipart/mixed",
                 "headers":[{"name":"From","value":"\"Ann\" <Ann@Example.COM>"},{"name":"To","value":"me@x.com"},{"name":"Subject","value":"Report"},{"name":"Date","value":"Fri, 2 Oct 2026"},
                   {"name":"Authentication-Results","value":"mx.google.com; dmarc=pass header.from=example.com"},
                   {"name":"Authentication-Results","value":"evil.example; dmarc=pass"},
                   {"name":"authentication-results","value":"  MX.GOOGLE.COM ; spf=pass"}],
                 "body":{"size":0},
                 "parts":[
                   {"mimeType":"multipart/alternative","body":{"size":0},"parts":[
                     {"mimeType":"text/plain","body":{"size":3,"data":"${b64url("plain")}"}},
                     {"mimeType":"text/html","body":{"size":3,"data":"${b64url(html)}"}}]},
                   {"mimeType":"text/html","filename":"attachment.html","body":{"size":3,"data":"${b64url("<b>attachment</b>")}"}}]}}
                """.trimIndent(),
            )
        }
        val message = client().getMessage("m1")
        assertEquals(html, message.body)
        assertEquals(BodyType.Html, message.bodyType)
        assertEquals("ann@example.com", message.fromAddress)
        assertEquals("Report", message.subject)
        assertEquals("me@x.com", message.to)
        assertEquals("Fri, 2 Oct 2026", message.date)
        // Only Gmail's own results count; other servers' headers can be forged by the sender.
        assertEquals(listOf("mx.google.com; dmarc=pass header.from=example.com", "  MX.GOOGLE.COM ; spf=pass"), message.authenticationResults)
    }

    @Test
    fun fallsBackToPlainTextThenSnippet() = runTest {
        serve {
            MockResponse().setBody(
                """{"id":"m","threadId":"t","snippet":"just a snippet","payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"bare@x.com"}],"body":{"size":5,"data":"${b64url("plain body")}"}}}""",
            )
        }
        val plain = client().getMessage("m")
        assertEquals("plain body", plain.body)
        assertEquals(BodyType.Text, plain.bodyType)
        assertEquals("bare@x.com", plain.fromAddress)
        assertEquals("(no subject)", plain.subject)

        serve { MockResponse().setBody("""{"id":"m","threadId":"t","snippet":"just a snippet","payload":{"mimeType":"multipart/mixed","headers":[],"body":{"size":0}}}""") }
        val empty = client().getMessage("m")
        assertEquals("just a snippet", empty.body)
        assertEquals(BodyType.Text, empty.bodyType)
    }

    @Test
    fun retriesOnceWithAFreshTokenAfter401() = runTest {
        serve { request ->
            if (request.getHeader("Authorization") == "Bearer fresh-token") MockResponse().setBody("{}") else MockResponse().setResponseCode(401).setBody("expired")
        }
        client().listInbox(5)
        assertEquals(listOf(false, true), tokenRequests.toList())
    }

    @Test
    fun surfacesApiErrors() = runTest {
        serve { MockResponse().setResponseCode(403).setBody("denied") }
        val error = runCatching { client().listInbox(5) }.exceptionOrNull()
        assertTrue(error is GmailException)
        assertEquals("Gmail API 403: denied", error!!.message)
        assertEquals(403, (error as GmailException).status)
    }

    @Test
    fun correspondenceQueryEscapesPlusAddressesAndIgnoresTheCurrentMessage() = runTest {
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        serve { request ->
            seen.add(request)
            MockResponse().setBody("""{"messages":[{"id":"current"}]}""")
        }
        assertFalse(client().hasCorrespondence("a+tag@x.com", "current"))
        // On the wire the "+" must be escaped, or Gmail would read it as a space.
        assertTrue(seen.first().path.orEmpty().contains("a%2Btag%40x.com"))
        assertEquals("""{from:"a+tag@x.com" to:"a+tag@x.com"}""", seen.first().requestUrl!!.queryParameter("q"))
        assertEquals("2", seen.first().requestUrl!!.queryParameter("maxResults"))

        serve { MockResponse().setBody("""{"messages":[{"id":"current"},{"id":"other"}]}""") }
        assertTrue(client().hasCorrespondence("a@x.com", "current"))
    }

    @Test
    fun profileEmail() = runTest {
        serve { MockResponse().setBody("""{"emailAddress":"me@gmail.com","messagesTotal":5}""") }
        assertEquals("me@gmail.com", client().profileEmail())
    }

    @Test
    fun throwsTypedErrorForServerFailures() {
        serve { MockResponse().setResponseCode(500).setBody("oops") }
        assertThrows(GmailException::class.java) { kotlinx.coroutines.runBlocking { client().getMessage("x") } }
    }
}
