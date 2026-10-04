package com.ospfranco.safemail.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ospfranco.safemail.mail.MailLabel
import com.ospfranco.safemail.mail.MessageDetail
import com.ospfranco.safemail.mail.MessageSummary
import com.ospfranco.safemail.mail.OutgoingMessage
import com.ospfranco.safemail.mail.Services
import com.ospfranco.safemail.mail.UserProfile
import com.ospfranco.safemail.mail.makeReply
import com.ospfranco.safemail.mail.Mime
import com.ospfranco.safemail.security.FoundLink
import com.ospfranco.safemail.security.ScannedLink
import com.ospfranco.safemail.security.SecurityEvaluation
import com.ospfranco.safemail.security.SecurityScanner
import com.ospfranco.safemail.security.SenderChecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Throwable.display(): String = message ?: toString()

class SessionViewModel(
    private val services: Services,
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
        showNotice(notice)
    }

    var user by mutableStateOf<UserProfile?>(null)
        private set
    var messages by mutableStateOf<List<MessageSummary>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch { start() }
    }

    suspend fun start() {
        try {
            user = services.auth.restoreSession()
            if (user != null) loadInbox()
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.display()
        }
        isLoading = false
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
                is MessageAction.Move -> services.mail.move(id, action.label)
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

    fun onLinksFound(found: List<FoundLink>) {
        viewModelScope.launch { scan(found) }
    }

    suspend fun scan(found: List<FoundLink>) {
        val current = message ?: return
        if (isScanning) return
        isScanning = true
        val (scanned, sender) = kotlinx.coroutines.coroutineScope {
            val scanned = async { SecurityScanner.scanLinks(found) }
            val sender = async { SenderChecks.checkSender(current, services.mail) }
            scanned.await() to sender.await()
        }
        links = scanned
        evaluation = SecurityScanner.evaluateEmail(sender, scanned)
    }
}

sealed interface MessageAction {
    data object Archive : MessageAction

    data object Trash : MessageAction

    data class Move(val label: MailLabel) : MessageAction

    /** What the user sees after it worked. */
    val notice: String
        get() = when (this) {
            Archive -> "Archived"
            Trash -> "Moved to Trash"
            is Move -> "Moved to ${label.name}"
        }

    val failureNotice: String
        get() = when (this) {
            Archive -> "Couldn't archive the email"
            Trash -> "Couldn't delete the email"
            is Move -> "Couldn't move the email"
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
