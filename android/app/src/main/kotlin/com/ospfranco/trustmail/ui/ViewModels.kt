package com.ospfranco.trustmail.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ospfranco.trustmail.R
import com.ospfranco.trustmail.Strings
import com.ospfranco.trustmail.mail.InboxSnapshot
import com.ospfranco.trustmail.mail.InboxSnapshotStore
import com.ospfranco.trustmail.mail.MailLabel
import com.ospfranco.trustmail.mail.MemoryInboxSnapshotStore
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.mail.Mime
import com.ospfranco.trustmail.mail.OutgoingMessage
import com.ospfranco.trustmail.mail.Services
import com.ospfranco.trustmail.mail.UserProfile
import com.ospfranco.trustmail.mail.bareAddress
import com.ospfranco.trustmail.mail.makeReply
import com.ospfranco.trustmail.security.FoundLink
import com.ospfranco.trustmail.security.ScannedLink
import com.ospfranco.trustmail.security.SecurityEvaluation
import com.ospfranco.trustmail.security.SecurityScanner
import com.ospfranco.trustmail.security.SenderChecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Throwable.display(): String = message ?: toString()

class SessionViewModel(
    private val services: Services,
    private val snapshots: InboxSnapshotStore = MemoryInboxSnapshotStore(),
    private val noticeDurationMs: Long = 2500,
) : ViewModel() {
    /** A short message shown over whatever screen is open ("Archived", "Message sent", ...). */
    var notice by mutableStateOf<String?>(null)
        private set
    private var noticeJob: Job? = null

    fun showNotice(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(noticeDurationMs)
            notice = null
        }
    }

    /** Drops a message that was archived, deleted or moved out of the inbox. */
    fun removeMessage(id: String, notice: String) {
        messages = messages.filter { it.id != id }
        viewModelScope.launch { saveSnapshot() }
        showNotice(notice)
    }

    var user by mutableStateOf<UserProfile?>(null)
        private set
    var messages by mutableStateOf<List<MessageSummary>>(emptyList())
        private set
    /** Ids of inbox emails from someone you've never exchanged email with, found in the background after the inbox loads. */
    var firstMessages by mutableStateOf<Set<String>>(emptySet())
        private set
    private var historyJob: Job? = null
    var isLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The inbox couldn't be refreshed, so what's shown is the copy saved on the device. */
    var showsSavedInbox by mutableStateOf(false)
        private set
    /** Whether [messages] came from the snapshot rather than from Gmail. */
    private var messagesAreSaved = false

    init {
        viewModelScope.launch { start() }
    }

    suspend fun start() {
        try {
            val restored = services.auth.restoreSession()
            user = restored
            if (restored != null) {
                // Show the last saved inbox straight away; the refresh replaces it.
                snapshots.load()?.takeIf { it.account == restored.email }?.let {
                    messages = it.messages
                    firstMessages = it.firstMessages
                    messagesAreSaved = true
                }
                loadInbox()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.display()
        }
        isLoading = false
    }

    fun refresh() {
        viewModelScope.launch { loadInbox() }
    }

    suspend fun loadInbox() {
        isLoading = true
        error = null
        try {
            messages = services.mail.listInbox(20)
            services.senders.record(messages)
            messagesAreSaved = false
            showsSavedInbox = false
            saveSnapshot()
            historyJob?.cancel()
            historyJob = viewModelScope.launch { loadSenderHistory() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.display()
            showsSavedInbox = messagesAreSaved
        }
        isLoading = false
    }

    /** Keeps the inbox on the device, for the next launch and for when Gmail can't be reached. */
    private suspend fun saveSnapshot() {
        val account = user?.email ?: return
        val ids = messages.map { it.id }.toSet()
        snapshots.save(InboxSnapshot(account, messages, firstMessages intersect ids))
    }

    /**
     * Finds the inbox emails whose sender is new to you: the same lookup as the email header's sender history,
     * one per email. A lookup that fails marks nothing.
     */
    suspend fun loadSenderHistory() {
        val first = coroutineScope {
            messages.map { message ->
                async {
                    val known = try {
                        SenderChecks.isKnownSender(bareAddress(message.from), message.id, services.mail, services.senders)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    message.id.takeIf { known == false }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        firstMessages = first
        saveSnapshot()
    }

    fun signIn() {
        viewModelScope.launch {
            error = null
            try {
                user = services.auth.signIn()
                if (user != null) loadInbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.display()
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            services.auth.signOut()
            user = null
            messages = emptyList()
            historyJob?.cancel()
            firstMessages = emptySet()
            showsSavedInbox = false
            messagesAreSaved = false
            // The saved inbox and the known senders are private to the account that signed out.
            snapshots.clear()
            services.senders.clear()
        }
    }
}

class MessageViewModel(val id: String, private val services: Services) : ViewModel() {
    var message by mutableStateOf<MessageDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var links by mutableStateOf<List<ScannedLink>?>(null)
        private set
    var evaluation by mutableStateOf<SecurityEvaluation?>(null)
        private set
    private var isScanning = false

    /** True while archive, delete or move is running, so the buttons can't be pressed twice. */
    var isBusy by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { load() }
    }

    /** The reply to this email, ready to edit. */
    val reply: OutgoingMessage? get() = message?.makeReply()

    suspend fun loadMoveTargets(): List<MailLabel> = services.mail.moveTargets()

    /** Runs the action; returns whether it worked. */
    suspend fun perform(action: MessageAction): Boolean {
        if (isBusy) return false
        isBusy = true
        return try {
            when (action) {
                MessageAction.Archive -> services.mail.archive(id)
                MessageAction.Trash -> services.mail.trash(id)
                is MessageAction.Move -> {
                    services.mail.move(id, action.label)
                    // Mail in Spam doesn't count as having heard from someone.
                    val current = message
                    if (action.label.id == MailLabel.Spam.id && current != null) services.senders.forget(current.fromAddress)
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            isBusy = false
        }
    }

    suspend fun load() {
        try {
            message = services.mail.getMessage(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.display()
        }
    }

    fun onLinksFound(found: List<FoundLink>, ignoreFooterLinks: Boolean = false) {
        viewModelScope.launch { scan(found, ignoreFooterLinks) }
    }

    suspend fun scan(found: List<FoundLink>, ignoreFooterLinks: Boolean = false) {
        val current = message ?: return
        if (isScanning) return
        isScanning = true
        val (scanned, sender) = kotlinx.coroutines.coroutineScope {
            val scanned = async { SecurityScanner.scanLinks(found) }
            val sender = async { SenderChecks.checkSender(current, services.mail, services.senders) }
            scanned.await() to sender.await()
        }
        links = scanned
        evaluation = SecurityScanner.evaluateEmail(sender, scanned, ignoreFooterLinks)
    }
}

sealed interface MessageAction {
    data object Archive : MessageAction

    data object Trash : MessageAction

    data class Move(val label: MailLabel) : MessageAction

    /** What the user sees after it worked. */
    fun notice(strings: Strings): String = when (this) {
        Archive -> strings.get(R.string.notice_archived)
        Trash -> strings.get(R.string.notice_trashed)
        is Move -> strings.get(R.string.notice_moved, label.name)
    }

    fun failureNotice(strings: Strings): String = when (this) {
        Archive -> strings.get(R.string.notice_archive_failed)
        Trash -> strings.get(R.string.notice_trash_failed)
        is Move -> strings.get(R.string.notice_move_failed)
    }
}

class ComposeViewModel(private val original: OutgoingMessage, private val services: Services) : ViewModel() {
    var to by mutableStateOf(original.to)
    var subject by mutableStateOf(original.subject)
    var body by mutableStateOf(original.body)
    var isSending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val isReply: Boolean get() = original.inReplyTo != null
    val canSend: Boolean get() = Mime.isValidRecipientList(to) && !isSending

    /** Sends the message; returns whether it went out. */
    suspend fun send(): Boolean {
        if (!canSend) return false
        isSending = true
        error = null
        return try {
            services.mail.send(original.copy(to = to, subject = subject, body = body))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.display()
            false
        } finally {
            isSending = false
        }
    }
}
