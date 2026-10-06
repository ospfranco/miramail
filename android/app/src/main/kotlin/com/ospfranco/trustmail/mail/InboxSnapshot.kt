package com.ospfranco.trustmail.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The latest inbox, kept on the device so the app can show it at launch and when Gmail can't be reached. */
@Serializable
data class InboxSnapshot(
    /** Whose inbox this is: a snapshot is only shown to the account that saved it. */
    val account: String,
    val messages: List<MessageSummary>,
    /** Emails from someone the user had never exchanged email with (the inbox's yellow marker). */
    val firstMessages: Set<String>,
)

/** Where the snapshot is kept. The app uses a file; tests and demo runs use memory. */
interface InboxSnapshotStore {
    suspend fun load(): InboxSnapshot?

    suspend fun save(snapshot: InboxSnapshot)

    suspend fun clear()
}

/**
 * A JSON file in the app's private storage (which the app keeps out of backups: `allowBackup="false"`).
 * A file that can't be read is treated as no snapshot.
 */
class FileInboxSnapshotStore(directory: File) : InboxSnapshotStore {
    private val file = File(directory, "inbox_snapshot.json")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): InboxSnapshot? = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) json.decodeFromString<InboxSnapshot>(file.readText()) else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun save(snapshot: InboxSnapshot) = withContext(Dispatchers.IO) {
        try {
            // Written next to the real file, then moved over it, so a crash never leaves half a snapshot.
            val partial = File(file.parentFile, "${file.name}.partial")
            partial.writeText(json.encodeToString(InboxSnapshot.serializer(), snapshot))
            if (!partial.renameTo(file)) partial.delete()
        } catch (e: Exception) {
            // Best effort: without a snapshot the app still works, it just can't show the inbox offline.
        }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }
}

class MemoryInboxSnapshotStore(private var snapshot: InboxSnapshot? = null) : InboxSnapshotStore {
    override suspend fun load() = synchronized(this) { snapshot }

    override suspend fun save(snapshot: InboxSnapshot) = synchronized(this) { this.snapshot = snapshot }

    override suspend fun clear() = synchronized(this) { snapshot = null }
}
