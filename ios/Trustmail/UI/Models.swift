import Foundation
import Observation

struct Services: Sendable {
    let auth: AuthService
    let mail: MailService
    /// Senders seen in the inbox, so deleting their emails doesn't make them new again.
    let senders: KnownSenders

    init(auth: AuthService, mail: MailService, senders: KnownSenders = KnownSenders()) {
        self.auth = auth
        self.mail = mail
        self.senders = senders
    }
}

@MainActor
@Observable
final class SessionModel {
    let services: Services
    private(set) var user: UserProfile?
    private(set) var messages: [MessageSummary] = []
    /// Ids of inbox emails from someone you've never exchanged email with, found in the background after the inbox loads.
    private(set) var firstMessages: Set<String> = []
    private var historyTask: Task<Void, Never>?
    private(set) var isLoading = true
    private(set) var error: String?
    /// The inbox couldn't be refreshed, so what's shown is the copy saved on the device.
    private(set) var showsSavedInbox = false
    /// Whether `messages` came from the snapshot rather than from Gmail.
    private var messagesAreSaved = false
    private let snapshots: InboxSnapshotStore
    /// A short message shown over whatever screen is open ("Archived", "Message sent", ...).
    private(set) var notice: String?
    private var noticeTask: Task<Void, Never>?
    private let noticeDuration: Duration

    init(services: Services, snapshots: InboxSnapshotStore = MemoryInboxSnapshotStore(), noticeDuration: Duration = .seconds(2.5)) {
        self.services = services
        self.snapshots = snapshots
        self.noticeDuration = noticeDuration
    }

    func showNotice(_ text: String) {
        notice = text
        noticeTask?.cancel()
        noticeTask = Task { [noticeDuration] in
            try? await Task.sleep(for: noticeDuration)
            if !Task.isCancelled { notice = nil }
        }
    }

    /// Drops a message that was archived, deleted or moved out of the inbox.
    func removeMessage(_ id: String, notice: String) {
        messages.removeAll { $0.id == id }
        saveSnapshot()
        showNotice(notice)
    }

    func start() async {
        do {
            user = try await services.auth.restoreSession()
            if let user {
                // Show the last saved inbox straight away; the refresh replaces it.
                if let snapshot = snapshots.load(), snapshot.account == user.email {
                    messages = snapshot.messages
                    firstMessages = snapshot.firstMessages
                    messagesAreSaved = true
                }
                await loadInbox()
            }
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    func loadInbox() async {
        isLoading = true
        error = nil
        do {
            messages = try await services.mail.listInbox(maxResults: 20)
            services.senders.record(messages)
            messagesAreSaved = false
            showsSavedInbox = false
            saveSnapshot()
            historyTask?.cancel()
            historyTask = Task { await loadSenderHistory() }
        } catch {
            self.error = error.localizedDescription
            showsSavedInbox = messagesAreSaved
        }
        isLoading = false
    }

    /// Keeps the inbox on the device, for the next launch and for when Gmail can't be reached.
    private func saveSnapshot() {
        guard let user else { return }
        let ids = Set(messages.map(\.id))
        snapshots.save(InboxSnapshot(account: user.email, messages: messages, firstMessages: firstMessages.intersection(ids)))
    }

    /// Finds the inbox emails whose sender is new to you: the same lookup as the email header's sender history,
    /// one per email. A lookup that fails marks nothing.
    func loadSenderHistory() async {
        let (mail, senders) = (services.mail, services.senders)
        let first = await withTaskGroup(of: String?.self) { group in
            for message in messages {
                group.addTask {
                    let known = try? await SenderChecks.isKnownSender(bareAddress(message.from), excluding: message.id, mail: mail, senders: senders)
                    return known == false ? message.id : nil
                }
            }
            var ids: Set<String> = []
            for await id in group { if let id { ids.insert(id) } }
            return ids
        }
        if !Task.isCancelled {
            firstMessages = first
            saveSnapshot()
        }
    }

    func signIn() async {
        error = nil
        do {
            user = try await services.auth.signIn()
            if user != nil { await loadInbox() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func signOut() async {
        await services.auth.signOut()
        user = nil
        messages = []
        historyTask?.cancel()
        firstMessages = []
        showsSavedInbox = false
        messagesAreSaved = false
        // The saved inbox and the known senders are private to the account that signed out.
        snapshots.clear()
        services.senders.clear()
    }
}

@MainActor
@Observable
final class MessageModel {
    let id: String
    fileprivate let services: Services
    private(set) var message: MessageDetail?
    private(set) var error: String?
    private(set) var links: [ScannedLink]?
    private(set) var evaluation: SecurityEvaluation?
    private var isScanning = false
    /// True while archive, delete or move is running, so the buttons can't be pressed twice.
    private(set) var isBusy = false

    init(id: String, services: Services) {
        self.id = id
        self.services = services
    }

    func load() async {
        do {
            message = try await services.mail.getMessage(id: id)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func linksFound(_ found: [FoundLink], ignoreFooterLinks: Bool = false) async {
        guard let message, !isScanning else { return }
        isScanning = true
        async let scanned = SecurityScanner.scanLinks(found)
        async let senderChecks = SenderChecks.checkSender(message, mail: services.mail, senders: services.senders)
        let (scannedLinks, sender) = await (scanned, senderChecks)
        links = scannedLinks
        evaluation = SecurityScanner.evaluateEmail(senderChecks: sender, links: scannedLinks, ignoreFooterLinks: ignoreFooterLinks)
    }
}

enum MessageAction {
    case archive
    case trash
    case move(MailLabel)

    /// What the user sees after it worked.
    var notice: String {
        switch self {
        case .archive: String(localized: "Archived")
        case .trash: String(localized: "Moved to Trash")
        case .move(let label): String(localized: "Moved to \(label.name)")
        }
    }

    var failureNotice: String {
        switch self {
        case .archive: String(localized: "Couldn't archive the email")
        case .trash: String(localized: "Couldn't delete the email")
        case .move: String(localized: "Couldn't move the email")
        }
    }
}

extension MessageModel {
    func loadMoveTargets() async throws -> [MailLabel] {
        try await services.mail.moveTargets()
    }

    /// Runs the action; returns whether it worked.
    func perform(_ action: MessageAction) async -> Bool {
        guard !isBusy else { return false }
        isBusy = true
        defer { isBusy = false }
        do {
            switch action {
            case .archive: try await services.mail.archive(id: id)
            case .trash: try await services.mail.trash(id: id)
            case .move(let label):
                try await services.mail.move(id: id, to: label)
                // Mail in Spam doesn't count as having heard from someone.
                if label.id == MailLabel.spam.id, let message { services.senders.forget(message.fromAddress) }
            }
            return true
        } catch {
            return false
        }
    }

    /// The reply to this email, ready to edit.
    var reply: OutgoingMessage? { message?.makeReply() }
}

@MainActor
@Observable
final class ComposeModel {
    private let services: Services
    private let original: OutgoingMessage
    var to: String
    var subject: String
    var body: String
    private(set) var isSending = false
    private(set) var error: String?

    init(draft: OutgoingMessage, services: Services) {
        self.services = services
        original = draft
        to = draft.to
        subject = draft.subject
        body = draft.body
    }

    var canSend: Bool { Mime.isValidRecipientList(to) && !isSending }

    /// Sends the message; returns whether it went out.
    func send() async -> Bool {
        guard canSend else { return false }
        isSending = true
        error = nil
        defer { isSending = false }
        var message = original
        message.to = to
        message.subject = subject
        message.body = body
        do {
            try await services.mail.send(message)
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }
}
