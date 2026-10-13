package com.ospfranco.trustmail.mail

import com.ospfranco.trustmail.security.senderFailed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Senders whose email has been in the inbox, kept on the device. Gmail's search can only see mail that still exists,
 * so without this a sender becomes "new" again once you delete their emails and the Trash is emptied.
 * Maps each address to the first email seen from it: that email itself doesn't make its sender known.
 *
 * Kept in memory only when [directory] is null (tests and demo runs). Otherwise a JSON file in the app's private
 * storage, which the app keeps out of backups (`allowBackup="false"`), like the saved inbox.
 */
class KnownSenders(directory: File? = null) {
    private val file = directory?.let { File(it, "known_senders.json") }
    private val mutex = Mutex()
    private var senders: Map<String, String>? = null

    /** Whether an email other than [messageId] from [address] has been seen. */
    suspend fun knows(address: String, besides: String): Boolean = mutex.withLock {
        val first = loaded()[address] ?: return@withLock false
        first != besides
    }

    /**
     * Remembers the senders of these inbox emails. Senders that failed Gmail's checks are skipped, so a forged
     * address can't make itself known.
     */
    suspend fun record(messages: List<MessageSummary>) = mutex.withLock {
        val updated = loaded().toMutableMap()
        for (message in messages) {
            if (message.senderFailed) continue
            val address = bareAddress(message.from)
            if (address.isNotEmpty()) updated.putIfAbsent(address, message.id)
        }
        if (updated != senders) save(updated)
    }

    /** Forgets a sender, e.g. after their email was moved to Spam. */
    suspend fun forget(address: String) = mutex.withLock {
        val current = loaded()
        if (address in current) save(current - address)
    }

    suspend fun clear() = mutex.withLock {
        senders = emptyMap()
        val file = file ?: return@withLock
        withContext(Dispatchers.IO) { file.delete() }
        Unit
    }

    /** Call with the lock held. A file that can't be read counts as no senders. */
    private suspend fun loaded(): Map<String, String> {
        senders?.let { return it }
        val file = file
        val read = if (file == null) null else withContext(Dispatchers.IO) {
            try {
                if (file.exists()) json.decodeFromString(serializer, file.readText()) else null
            } catch (e: Exception) {
                null
            }
        }
        return read.orEmpty().also { senders = it }
    }

    /** Call with the lock held. */
    private suspend fun save(updated: Map<String, String>) {
        senders = updated
        val file = file ?: return
        withContext(Dispatchers.IO) {
            try {
                // Written next to the real file, then moved over it, so a crash never leaves half a file.
                val partial = File(file.parentFile, "${file.name}.partial")
                partial.writeText(json.encodeToString(serializer, updated))
                if (!partial.renameTo(file)) partial.delete()
            } catch (e: Exception) {
                // Best effort: without the file, history falls back to Gmail's search alone.
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}
