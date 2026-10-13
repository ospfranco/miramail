import Foundation

/// Senders whose email has been in the inbox, kept on the device. Gmail's search can only see mail that still exists,
/// so without this a sender becomes "new" again once you delete their emails and the Trash is emptied.
/// Maps each address to the first email seen from it: that email itself doesn't make its sender known.
final class KnownSenders: @unchecked Sendable {
    private let url: URL?
    private let lock = NSLock()
    private var senders: [String: String]?

    /// Kept in memory only when `directory` is nil (tests and demo runs).
    init(directory: URL? = nil) {
        url = directory?.appending(path: "known-senders.json")
    }

    static func file() -> KnownSenders { KnownSenders(directory: URL.applicationSupportDirectory) }

    /// Whether an email other than `messageId` from `address` has been seen.
    func knows(_ address: String, besides messageId: String) -> Bool {
        lock.withLock {
            guard let first = loaded()[address] else { return false }
            return first != messageId
        }
    }

    /// Remembers the senders of these inbox emails. Senders that failed Gmail's checks are skipped, so a forged
    /// address can't make itself known.
    func record(_ messages: [MessageSummary]) {
        lock.withLock {
            var senders = loaded()
            for message in messages where !message.senderFailed {
                let address = bareAddress(message.from)
                if !address.isEmpty, senders[address] == nil { senders[address] = message.id }
            }
            if senders != self.senders { save(senders) }
        }
    }

    /// Forgets a sender, e.g. after their email was moved to Spam.
    func forget(_ address: String) {
        lock.withLock {
            var senders = loaded()
            if senders.removeValue(forKey: address) != nil { save(senders) }
        }
    }

    func clear() {
        lock.withLock {
            senders = [:]
            if let url { try? FileManager.default.removeItem(at: url) }
        }
    }

    /// Call with the lock held. A file that can't be read counts as no senders.
    private func loaded() -> [String: String] {
        if let senders { return senders }
        let read = url.flatMap { try? Data(contentsOf: $0) }.flatMap { try? JSONDecoder().decode([String: String].self, from: $0) }
        senders = read ?? [:]
        return senders!
    }

    /// Call with the lock held. Addresses are private, so the file is encrypted while the device is locked and left
    /// out of backups, like the saved inbox.
    private func save(_ senders: [String: String]) {
        self.senders = senders
        guard let url, let data = try? JSONEncoder().encode(senders) else { return }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var file = url
            try file.setResourceValues(values)
        } catch {
            // Best effort: without the file, history falls back to Gmail's search alone.
        }
    }
}
