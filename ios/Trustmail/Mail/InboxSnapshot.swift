import Foundation

/// The latest inbox, kept on the device so the app can show it at launch and when Gmail can't be reached.
struct InboxSnapshot: Codable, Equatable, Sendable {
    /// Whose inbox this is: a snapshot is only shown to the account that saved it.
    let account: String
    let messages: [MessageSummary]
    /// Emails from someone the user had never exchanged email with (the inbox's yellow marker).
    let firstMessages: Set<String>
}

/// Where the snapshot is kept. The app uses a file; tests and demo runs use memory.
protocol InboxSnapshotStore: Sendable {
    func load() -> InboxSnapshot?
    func save(_ snapshot: InboxSnapshot)
    func clear()
}

/// A JSON file in Application Support. Email subjects and senders are private, so the file is encrypted while the
/// device is locked and left out of backups. A file that can't be read is treated as no snapshot.
final class FileInboxSnapshotStore: InboxSnapshotStore {
    private let url: URL

    init(directory: URL = URL.applicationSupportDirectory) {
        url = directory.appending(path: "inbox-snapshot.json")
    }

    func load() -> InboxSnapshot? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(InboxSnapshot.self, from: data)
    }

    func save(_ snapshot: InboxSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var file = url
            try file.setResourceValues(values)
        } catch {
            // Best effort: without a snapshot the app still works, it just can't show the inbox offline.
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}

final class MemoryInboxSnapshotStore: InboxSnapshotStore, @unchecked Sendable {
    private let lock = NSLock()
    private var snapshot: InboxSnapshot?

    init(_ snapshot: InboxSnapshot? = nil) {
        self.snapshot = snapshot
    }

    func load() -> InboxSnapshot? { lock.withLock { snapshot } }
    func save(_ snapshot: InboxSnapshot) { lock.withLock { self.snapshot = snapshot } }
    func clear() { lock.withLock { snapshot = nil } }
}
