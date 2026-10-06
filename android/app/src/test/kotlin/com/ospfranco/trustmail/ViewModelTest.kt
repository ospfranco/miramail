package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.AuthService
import com.ospfranco.trustmail.mail.GmailException
import com.ospfranco.trustmail.mail.MailLabel
import com.ospfranco.trustmail.mail.MailService
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.mail.OutgoingMessage
import com.ospfranco.trustmail.mail.Services
import com.ospfranco.trustmail.mail.UserProfile
import com.ospfranco.trustmail.security.FoundLink
import com.ospfranco.trustmail.security.Verdict
import com.ospfranco.trustmail.ui.MessageViewModel
import com.ospfranco.trustmail.ui.SessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class FakeAuth(
    private val restored: UserProfile? = null,
    private val signedIn: UserProfile? = null,
    private val restoreFails: Boolean = false,
) : AuthService {
    override suspend fun restoreSession(): UserProfile? {
        if (restoreFails) throw GmailException(500, "no keychain")
        return restored
    }

    override suspend fun signIn() = signedIn
    override suspend fun signOut() {}
}

private class OneMessageInbox : MailService {
    override suspend fun listInbox(maxResults: Int) = listOf(MessageSummary("1", "t", "a@x.com", "s", "", false))
    override suspend fun getMessage(id: String): MessageDetail = makeMessage(id = id)
    override suspend fun hasCorrespondence(address: String, excludingMessageId: String) = false
    override suspend fun trash(id: String) {}
    override suspend fun archive(id: String) {}
    override suspend fun move(id: String, label: MailLabel) {}
    override suspend fun moveTargets() = emptyList<MailLabel>()
    override suspend fun send(message: OutgoingMessage) {}
}

/** An inbox with a known sender, a new one, and one whose history lookup fails. */
private class HistoryInbox : MailService {
    override suspend fun listInbox(maxResults: Int) =
        listOf("1" to "Known <known@x.com>", "2" to "New <new@x.com>", "3" to "broken@x.com").map { (id, from) ->
            MessageSummary(id, id, from, "s", "", false)
        }
    override suspend fun getMessage(id: String): MessageDetail = makeMessage(id = id)
    override suspend fun hasCorrespondence(address: String, excludingMessageId: String): Boolean {
        if (address == "broken@x.com") throw GmailException(500, "boom")
        return address == "known@x.com"
    }
    override suspend fun trash(id: String) {}
    override suspend fun archive(id: String) {}
    override suspend fun move(id: String, label: MailLabel) {}
    override suspend fun moveTargets() = emptyList<MailLabel>()
    override suspend fun send(message: OutgoingMessage) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    @get:Rule val psl = PublicSuffixRule()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun session(auth: AuthService, mail: MailService = FakeMail()) = SessionViewModel(Services(auth, mail))

    @Test
    fun signedOutStartShowsSignIn() = runTest {
        val model = session(FakeAuth())
        assertTrue(model.isLoading)
        advanceUntilIdle()
        assertNull(model.user)
        assertFalse(model.isLoading)
        assertNull(model.error)
    }

    @Test
    fun restoredSessionLoadsInbox() = runTest {
        val model = session(FakeAuth(restored = UserProfile("me@x.com")), OneMessageInbox())
        advanceUntilIdle()
        assertEquals(UserProfile("me@x.com"), model.user)
        assertEquals(listOf("1"), model.messages.map { it.id })
        assertFalse(model.isLoading)
    }

    @Test
    fun inboxMarksEmailsFromNewSenders() = runTest {
        val model = session(FakeAuth(restored = UserProfile("me@x.com")), HistoryInbox())
        advanceUntilIdle()
        // Only the sender you've never written with; a lookup that fails marks nothing.
        assertEquals(setOf("2"), model.firstMessages)
        model.signOut()
        advanceUntilIdle()
        assertTrue(model.firstMessages.isEmpty())
    }

    @Test
    fun restoreFailureIsShown() = runTest {
        val model = session(FakeAuth(restoreFails = true))
        advanceUntilIdle()
        assertNull(model.user)
        assertTrue(model.error!!.contains("no keychain"))
    }

    @Test
    fun signInThenSignOut() = runTest {
        val model = session(FakeAuth(signedIn = UserProfile("me@x.com")), OneMessageInbox())
        advanceUntilIdle()
        model.signIn()
        advanceUntilIdle()
        assertEquals("me@x.com", model.user?.email)
        assertEquals(1, model.messages.size)
        model.signOut()
        advanceUntilIdle()
        assertNull(model.user)
        assertTrue(model.messages.isEmpty())
    }

    @Test
    fun cancelledSignInStaysSignedOut() = runTest {
        val model = session(FakeAuth())
        advanceUntilIdle()
        model.signIn()
        advanceUntilIdle()
        assertNull(model.user)
        assertNull(model.error)
    }

    @Test
    fun messageModelScansLinksAndEvaluates() = runTest {
        val model = MessageViewModel("m1", Services(FakeAuth(), FakeMail(known = true)))
        advanceUntilIdle()
        assertEquals("m1", model.message?.id)
        assertNull(model.evaluation)

        model.onLinksFound(listOf(FoundLink("mailto:a@b.com", "mail"), FoundLink("http://10.0.0.1/", "ip")))
        // The scan does real (non-test-dispatcher) work only for network links; these need none.
        advanceUntilIdle()
        assertEquals(2, model.links?.size)
        assertEquals(Verdict.Danger, model.evaluation?.verdict)
        assertEquals(listOf("Sender not verified", "Known sender", "2 links scanned"), model.evaluation?.checks?.map { it.label })
    }

    @Test
    fun messageModelIgnoresASecondScan() = runTest {
        val model = MessageViewModel("m1", Services(FakeAuth(), FakeMail()))
        advanceUntilIdle()
        model.onLinksFound(emptyList())
        advanceUntilIdle()
        model.onLinksFound(listOf(FoundLink("http://10.0.0.1/", "")))
        advanceUntilIdle()
        assertEquals(true, model.links?.isEmpty())
    }
}
