package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.AuthService
import com.ospfranco.trustmail.mail.FileInboxSnapshotStore
import com.ospfranco.trustmail.mail.InboxSnapshot
import com.ospfranco.trustmail.mail.InboxSnapshotStore
import com.ospfranco.trustmail.mail.MailLabel
import com.ospfranco.trustmail.mail.MailService
import com.ospfranco.trustmail.mail.MemoryInboxSnapshotStore
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.mail.OutgoingMessage
import com.ospfranco.trustmail.mail.Services
import com.ospfranco.trustmail.mail.UserProfile
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** A Gmail that is either reachable (and has one new email) or offline. */
private class ReachableInbox(private val offline: Boolean) : MailService {
    override suspend fun listInbox(maxResults: Int): List<MessageSummary> {
        if (offline) throw IOException("Unable to resolve host")
        return listOf(MessageSummary("new", "new", "a@x.com", "s", "", false))
    }
    override suspend fun getMessage(id: String): MessageDetail = makeMessage(id = id)
    override suspend fun hasCorrespondence(address: String, excludingMessageId: String) = true
    override suspend fun trash(id: String) {}
    override suspend fun archive(id: String) {}
    override suspend fun move(id: String, label: MailLabel) {}
    override suspend fun moveTargets() = emptyList<MailLabel>()
    override suspend fun send(message: OutgoingMessage) {}
}

private class SignedIn(private val user: UserProfile) : AuthService {
    override suspend fun restoreSession() = user
    override suspend fun signIn() = user
    override suspend fun signOut() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class InboxSnapshotTest {
    @get:Rule val folder = TemporaryFolder()

    private val me = UserProfile("me@x.com")

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun saved(account: String = "me@x.com") = InboxSnapshot(
        account,
        listOf(MessageSummary("old", "old", "b@x.com", "s", "", true)),
        setOf("old"),
    )

    private fun session(store: InboxSnapshotStore, offline: Boolean) =
        SessionViewModel(Services(SignedIn(me), ReachableInbox(offline)), store)

    @Test
    fun showsTheSavedInboxWhenGmailCantBeReached() = runTest {
        val model = session(MemoryInboxSnapshotStore(saved()), offline = true)
        advanceUntilIdle()
        assertEquals(listOf("old"), model.messages.map { it.id })
        assertEquals(setOf("old"), model.firstMessages)
        assertTrue(model.showsSavedInbox)
        assertNotNull(model.error)
    }

    @Test
    fun aRefreshReplacesTheSavedInboxAndSavesTheNewOne() = runTest {
        val store = MemoryInboxSnapshotStore(saved())
        val model = session(store, offline = false)
        advanceUntilIdle()
        assertEquals(listOf("new"), model.messages.map { it.id })
        assertFalse(model.showsSavedInbox)
        assertEquals(listOf("new"), store.load()?.messages?.map { it.id })
        assertTrue("marks for emails no longer in the inbox are dropped", store.load()!!.firstMessages.isEmpty())

        model.removeMessage("new", "Archived")
        advanceUntilIdle()
        assertTrue(store.load()!!.messages.isEmpty())
    }

    @Test
    fun anotherAccountsInboxIsNeverShown() = runTest {
        val model = session(MemoryInboxSnapshotStore(saved(account = "other@x.com")), offline = true)
        advanceUntilIdle()
        assertTrue(model.messages.isEmpty())
        assertFalse(model.showsSavedInbox)
    }

    @Test
    fun signingOutDeletesTheSavedInbox() = runTest {
        val store = MemoryInboxSnapshotStore(saved())
        val model = session(store, offline = false)
        advanceUntilIdle()
        model.signOut()
        advanceUntilIdle()
        assertNull(store.load())
        assertFalse(model.showsSavedInbox)
    }

    @Test
    fun fileStoreKeepsTheInboxAndIgnoresAGarbledFile() = runTest {
        val directory = folder.newFolder()
        val store = FileInboxSnapshotStore(directory)
        assertNull(store.load())
        store.save(saved())
        assertEquals(saved(), FileInboxSnapshotStore(directory).load())

        File(directory, "inbox_snapshot.json").writeText("not json")
        assertNull(store.load())
        store.save(saved())
        store.clear()
        assertNull(store.load())
    }
}
