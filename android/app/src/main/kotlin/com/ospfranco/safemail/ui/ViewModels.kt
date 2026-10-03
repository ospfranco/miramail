package com.ospfranco.safemail.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ospfranco.safemail.mail.MessageDetail
import com.ospfranco.safemail.mail.MessageSummary
import com.ospfranco.safemail.mail.Services
import com.ospfranco.safemail.mail.UserProfile
import com.ospfranco.safemail.security.FoundLink
import com.ospfranco.safemail.security.ScannedLink
import com.ospfranco.safemail.security.SecurityEvaluation
import com.ospfranco.safemail.security.SecurityScanner
import com.ospfranco.safemail.security.SenderChecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private fun Throwable.display(): String = message ?: toString()

class SessionViewModel(private val services: Services) : ViewModel() {
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

class MessageViewModel(private val id: String, private val services: Services) : ViewModel() {
    var message by mutableStateOf<MessageDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var links by mutableStateOf<List<ScannedLink>?>(null)
        private set
    var evaluation by mutableStateOf<SecurityEvaluation?>(null)
        private set
    private var isScanning = false

    init {
        viewModelScope.launch { load() }
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
