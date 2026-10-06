import Foundation
import Observation

struct Services: Sendable {
    let auth: AuthService
    let mail: MailService
}

@MainActor
@Observable
final class SessionModel {
    let services: Services
    private(set) var user: UserProfile?
    private(set) var messages: [MessageSummary] = []
    private(set) var isLoading = true
    private(set) var error: String?
    /// A short message shown over whatever screen is open ("Archived", "Message sent", ...).
    private(set) var notice: String?
    private var noticeTask: Task<Void, Never>?
    private let noticeDuration: Duration

    init(services: Services, noticeDuration: Duration = .seconds(2.5)) {
        self.services = services
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
        showNotice(notice)
    }

    func start() async {
        do {
            user = try await services.auth.restoreSession()
            if user != nil { await loadInbox() }
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
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
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
        async let senderChecks = SenderChecks.checkSender(message, mail: services.mail)
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
        case .archive: "Archived"
        case .trash: "Moved to Trash"
        case .move(let label): "Moved to \(label.name)"
        }
    }

    var failureNotice: String {
        switch self {
        case .archive: "Couldn't archive the email"
        case .trash: "Couldn't delete the email"
        case .move: "Couldn't move the email"
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
            case .move(let label): try await services.mail.move(id: id, to: label)
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
