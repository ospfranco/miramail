package com.ospfranco.safemail

import com.ospfranco.safemail.mail.GmailClient
import com.ospfranco.safemail.mail.GmailException
import com.ospfranco.safemail.mail.MailLabel
import com.ospfranco.safemail.mail.MessageDetail
import com.ospfranco.safemail.mail.BodyType
import com.ospfranco.safemail.mail.MessageSummary
import com.ospfranco.safemail.mail.Mime
import com.ospfranco.safemail.mail.OutgoingMessage
import com.ospfranco.safemail.mail.Services
import com.ospfranco.safemail.mail.UserProfile
import com.ospfranco.safemail.mail.makeReply
import com.ospfranco.safemail.security.base64ToBytes
import com.ospfranco.safemail.security.utf8Decode
import com.ospfranco.safemail.ui.ComposeViewModel
import com.ospfranco.safemail.ui.MessageAction
import com.ospfranco.safemail.ui.MessageViewModel
import com.ospfranco.safemail.ui.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MimeTest {
    private fun raw(to: String, subject: String, body: String, inReplyTo: String? = null, references: String? = null) =
        Mime.raw(OutgoingMessage(to, subject, body, inReplyTo = inReplyTo, references = references))

    private val tail = "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"

    // Expected strings were produced independently with Python's base64 module.
    @Test
    fun buildsASimpleMessage() {
        assertEquals(
            "To: bob@example.com\r\nSubject: Hello\r\n${tail}SGkgQm9iDQpTZWNvbmQgbGluZQ==",
            raw("bob@example.com", "Hello", "Hi Bob\nSecond line"),
        )
    }

    @Test
    fun addsThreadingHeadersForAReply() {
        assertEquals(
            "To: ann@example.com\r\nSubject: Re: Report\r\nIn-Reply-To: <m2@x.com>\r\nReferences: <m1@x.com> <m2@x.com>\r\n${tail}VGhhbmtzIQ==",
            raw("ann@example.com", "Re: Report", "Thanks!", inReplyTo = "<m2@x.com>", references = "<m1@x.com> <m2@x.com>"),
        )
    }

    @Test
    fun encodesNonAsciiSubjectsAndBodies() {
        assertEquals(
            "To: bob@example.com\r\nSubject: =?UTF-8?B?Q2Fmw6kg4piV?=\r\n${tail}T2zDoSDinJM=",
            raw("bob@example.com", "Café ☕", "Olá ✓"),
        )
    }

    @Test
    fun stripsLineBreaksSoHeadersCannotBeInjected() {
        assertEquals("To: a@b.com\r\nSubject: HiBcc: evil@x.com\r\n${tail}eA==", raw("a@b.com", "Hi\r\nBcc: evil@x.com", "x"))
        assertFalse(raw("a@b.com\r\nBcc: evil@x.com", "s", "x").contains("\r\nBcc:"))
    }

    @Test
    fun wrapsTheBodyAt76Characters() {
        assertEquals(
            "To: a@b.com\r\nSubject: Long\r\n${tail}YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh\r\nYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==",
            raw("a@b.com", "Long", "a".repeat(100)),
        )
    }

    @Test
    fun keepsEveryRecipient() {
        assertEquals("To: a@b.com, Bob <b@c.org>\r\nSubject: Two\r\n${tail}eA==", raw("a@b.com, Bob <b@c.org>", "Two", "x"))
        assertTrue(raw("a@b.com; c@d.com", "Two", "x").startsWith("To: a@b.com, c@d.com\r\n"))
    }

    @Test
    fun validatesRecipients() {
        for (good in listOf("bob@example.com", "Bob <bob@example.com>", "a@b.com, c@d.org", "a@b.com; c@d.org", "  a@b.com  ", "first.last+tag@sub.example.co.uk")) {
            assertTrue(good, Mime.isValidRecipientList(good))
        }
        for (bad in listOf("", "   ", "bob", "bob@", "bob@example", "@example.com", "a@b.com, nope", "a b@c.com", "a@@b.com")) {
            assertFalse(bad, Mime.isValidRecipientList(bad))
        }
    }
}

class ReplyTest {
    private fun message(subject: String = "Report", replyTo: String = "", references: String = "<m1@x.com>") = MessageDetail(
        id = "m2", from = "Ann <ANN@Example.com>", fromAddress = "ann@example.com", to = "me@x.com", subject = subject,
        date = "", body = "", bodyType = BodyType.Text, authenticationResults = emptyList(),
        threadId = "t1", messageId = "<m2@x.com>", references = references, replyTo = replyTo,
    )

    @Test
    fun repliesToTheSenderInTheSameConversation() {
        assertEquals(
            OutgoingMessage("ann@example.com", "Re: Report", "", "t1", "<m2@x.com>", "<m1@x.com> <m2@x.com>"),
            message().makeReply(),
        )
    }

    @Test
    fun doesNotStackRePrefixes() {
        assertEquals("Re: Report", message(subject = "Re: Report").makeReply().subject)
        assertEquals("RE: Report", message(subject = "RE: Report").makeReply().subject)
        assertEquals("Re: Reminder", message(subject = "Reminder").makeReply().subject)
    }

    @Test
    fun prefersTheReplyToAddress() {
        assertEquals("help@example.com", message(replyTo = "Support <Help@Example.com>").makeReply().to)
        assertEquals("ann@example.com", message(replyTo = "   ").makeReply().to)
    }

    @Test
    fun handlesMissingSubjectAndReferences() {
        val reply = message(subject = "(no subject)", references = "").makeReply()
        assertEquals("Re: ", reply.subject)
        assertEquals("<m2@x.com>", reply.references)
        val bare = MessageDetail("x", "a@b.com", "a@b.com", "", "S", "", "", BodyType.Text, emptyList())
        assertNull(bare.makeReply().threadId)
        assertNull(bare.makeReply().inReplyTo)
        assertNull(bare.makeReply().references)
    }
}

/** The request bodies Gmail receives, read back from the mock server. */
class GmailActionTest {
    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(respond: (RecordedRequest) -> MockResponse = { MockResponse().setBody("{}") }): GmailClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                return respond(request)
            }
        }
        return GmailClient(baseUrl = server.url("/gmail/v1/users/me").toString().trimEnd('/')) { "token" }
    }

    private fun body(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun trashPostsToTheTrashEndpoint() = runTest {
        client().trash("m1")
        assertEquals("POST", seen[0].method)
        assertEquals("/gmail/v1/users/me/messages/m1/trash", seen[0].requestUrl!!.encodedPath)
    }

    @Test
    fun archiveRemovesTheInboxLabel() = runTest {
        client().archive("m1")
        assertEquals("/gmail/v1/users/me/messages/m1/modify", seen[0].requestUrl!!.encodedPath)
        val sent = body(seen[0])
        assertEquals(listOf("INBOX"), sent["removeLabelIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(sent["addLabelIds"])
    }

    @Test
    fun moveAddsTheLabelAndRemovesTheInboxLabel() = runTest {
        client().move("m1", MailLabel("Label_7", "Work"))
        assertEquals("/gmail/v1/users/me/messages/m1/modify", seen[0].requestUrl!!.encodedPath)
        val sent = body(seen[0])
        assertEquals(listOf("Label_7"), sent["addLabelIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("INBOX"), sent["removeLabelIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun moveTargetsAreTheUsersLabelsSortedThenSpam() = runTest {
        val json = """{"labels":[{"id":"INBOX","name":"INBOX","type":"system"},{"id":"L2","name":"work","type":"user"},{"id":"L1","name":"Receipts","type":"user"},{"id":"SENT","name":"SENT","type":"system"}]}"""
        assertEquals(
            listOf(MailLabel("L1", "Receipts"), MailLabel("L2", "work"), MailLabel("SPAM", "Spam")),
            client { MockResponse().setBody(json) }.moveTargets(),
        )
        assertEquals(listOf(MailLabel("SPAM", "Spam")), client { MockResponse().setBody("{}") }.moveTargets())
    }

    @Test
    fun sendPostsTheRawMessageInItsThread() = runTest {
        val message = OutgoingMessage("ann@example.com", "Re: Report", "Thanks!", "t1", "<m2@x.com>", "<m1@x.com> <m2@x.com>")
        client().send(message)
        assertEquals("POST", seen[0].method)
        assertEquals("/gmail/v1/users/me/messages/send", seen[0].requestUrl!!.encodedPath)
        val sent = body(seen[0])
        assertEquals("t1", sent["threadId"]!!.jsonPrimitive.content)
        val raw = sent["raw"]!!.jsonPrimitive.content
        assertTrue(!raw.contains("=") && !raw.contains("+") && !raw.contains("/"))
        assertEquals(Mime.raw(message), utf8Decode(base64ToBytes(raw)))
    }

    @Test
    fun sendWithoutAThreadOmitsIt() = runTest {
        client().send(OutgoingMessage("a@b.com", "Hi", "x"))
        assertNull(body(seen[0])["threadId"])
    }

    @Test
    fun actionErrorsSurface() = runTest {
        val error = runCatching { client { MockResponse().setResponseCode(403).setBody("denied") }.archive("m1") }.exceptionOrNull()
        assertTrue(error is GmailException)
        assertEquals("Gmail API 403: denied", error!!.message)
    }

    @Test
    fun gettingAMessageKeepsWhatAReplyNeeds() = runTest {
        val json = """{"id":"m9","threadId":"t9","snippet":"s","payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"Ann <a@x.com>"},{"name":"Message-Id","value":"<m9@x.com>"},{"name":"References","value":"<m8@x.com>"},{"name":"Reply-To","value":"help@x.com"}],"body":{"size":1,"data":"eA"}}}"""
        val message = client { MockResponse().setBody(json) }.getMessage("m9")
        assertEquals("t9", message.threadId)
        assertEquals("<m9@x.com>", message.messageId)
        assertEquals("<m8@x.com>", message.references)
        assertEquals("help@x.com", message.replyTo)
        assertEquals("help@x.com", message.makeReply().to)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MailActionModelTest {
    @get:Rule val psl = PublicSuffixRule()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Auth(private val user: UserProfile? = null) : com.ospfranco.safemail.mail.AuthService {
        override suspend fun restoreSession() = user
        override suspend fun signIn() = user
        override suspend fun signOut() {}
    }

    private fun services(mail: FakeMail) = Services(Auth(), mail)

    @Test
    fun noticeIsShownThenClears() = runTest {
        val session = SessionViewModel(services(FakeMail()), noticeDurationMs = 60)
        advanceUntilIdle()
        session.showNotice("Archived")
        runCurrent()
        assertEquals("Archived", session.notice)
        advanceTimeBy(100)
        runCurrent()
        assertNull(session.notice)
    }

    @Test
    fun aNewerNoticeReplacesTheOldOneAndRestartsTheClock() = runTest {
        val session = SessionViewModel(services(FakeMail()), noticeDurationMs = 300)
        advanceUntilIdle()
        session.showNotice("First")
        advanceTimeBy(200)
        session.showNotice("Second")
        advanceTimeBy(200) // 400ms after the first, but only 200 after the second
        runCurrent()
        assertEquals("Second", session.notice)
        advanceTimeBy(150)
        runCurrent()
        assertNull(session.notice)
    }

    @Test
    fun removingAMessageDropsItFromTheInboxAndShowsANotice() = runTest {
        val inbox = listOf("1", "2").map { MessageSummary(it, it, "a@x.com", "s$it", "", false) }
        val session = SessionViewModel(Services(Auth(UserProfile("me@x.com")), FakeMail(inbox = inbox)))
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), session.messages.map { it.id })
        session.removeMessage("1", "Archived")
        assertEquals(listOf("2"), session.messages.map { it.id })
        assertEquals("Archived", session.notice)
    }

    @Test
    fun actionsCallTheMailServiceAndReportSuccess() = runTest {
        val mail = FakeMail()
        val model = MessageViewModel("m1", services(mail))
        advanceUntilIdle()
        assertTrue(model.perform(MessageAction.Archive))
        assertTrue(model.perform(MessageAction.Trash))
        assertTrue(model.perform(MessageAction.Move(MailLabel("work", "Work"))))
        assertEquals(listOf("archive m1", "trash m1", "move m1 work"), mail.calls.toList())
        assertFalse(model.isBusy)
    }

    @Test
    fun aFailedActionReportsFailure() = runTest {
        val model = MessageViewModel("m1", services(FakeMail(failsActions = true)))
        advanceUntilIdle()
        assertFalse(model.perform(MessageAction.Archive))
        assertFalse(model.isBusy)
    }

    @Test
    fun actionNotices() {
        assertEquals("Archived", MessageAction.Archive.notice)
        assertEquals("Moved to Trash", MessageAction.Trash.notice)
        assertEquals("Moved to Work", MessageAction.Move(MailLabel("w", "Work")).notice)
        assertEquals("Couldn't archive the email", MessageAction.Archive.failureNotice)
        assertEquals("Couldn't delete the email", MessageAction.Trash.failureNotice)
        assertEquals("Couldn't move the email", MessageAction.Move(MailLabel("w", "Work")).failureNotice)
    }

    @Test
    fun moveTargetsComeFromTheService() = runTest {
        val model = MessageViewModel("m1", services(FakeMail()))
        advanceUntilIdle()
        assertEquals(listOf("Work", "Spam"), model.loadMoveTargets().map { it.name })
    }

    @Test
    fun theReplyIsPrefilledFromTheLoadedMessage() = runTest {
        val model = MessageViewModel("m1", services(FakeMail()))
        assertNull(model.reply)
        advanceUntilIdle()
        assertEquals("noreply@github.com", model.reply?.to)
        assertEquals("Re: Hi", model.reply?.subject)
    }

    @Test
    fun composeCanSendOnlyToValidRecipients() {
        val model = ComposeViewModel(OutgoingMessage("", "", ""), services(FakeMail()))
        assertFalse(model.canSend)
        model.to = "nope"
        assertFalse(model.canSend)
        model.to = "bob@example.com"
        assertTrue(model.canSend)
    }

    @Test
    fun composeSendsTheEditedMessageInTheOriginalThread() = runTest {
        val mail = FakeMail()
        val draft = OutgoingMessage("ann@example.com", "Re: Report", "", "t1", "<m2@x.com>", "<m1@x.com> <m2@x.com>")
        val model = ComposeViewModel(draft, services(mail))
        model.to = "ann@example.com, bob@example.com"
        model.body = "Thanks!"
        assertTrue(model.send())
        assertEquals(
            listOf(OutgoingMessage("ann@example.com, bob@example.com", "Re: Report", "Thanks!", "t1", "<m2@x.com>", "<m1@x.com> <m2@x.com>")),
            mail.sent.toList(),
        )
        assertNull(model.error)
        assertFalse(model.isSending)
    }

    @Test
    fun composeDoesNotSendWithoutAValidRecipient() = runTest {
        val mail = FakeMail()
        val model = ComposeViewModel(OutgoingMessage("bad", "s", "b"), services(mail))
        assertFalse(model.send())
        assertTrue(mail.calls.isEmpty())
    }

    @Test
    fun aFailedSendKeepsTheDraftAndShowsTheError() = runTest {
        val model = ComposeViewModel(OutgoingMessage("a@b.com", "s", "b"), services(FakeMail(failsActions = true)))
        assertFalse(model.send())
        assertTrue(model.error!!.contains("boom"))
        assertEquals("b", model.body)
        assertFalse(model.isSending)
    }
}
