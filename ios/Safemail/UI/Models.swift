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

    init(services: Services) {
        self.services = services
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
    private let id: String
    private let services: Services
    private(set) var message: MessageDetail?
    private(set) var error: String?
    private(set) var links: [ScannedLink]?
    private(set) var evaluation: SecurityEvaluation?
    private var isScanning = false

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

    func linksFound(_ found: [FoundLink]) async {
        guard let message, !isScanning else { return }
        isScanning = true
        async let scanned = SecurityScanner.scanLinks(found)
        async let senderChecks = SenderChecks.checkSender(message, mail: services.mail)
        let (scannedLinks, sender) = await (scanned, senderChecks)
        links = scannedLinks
        evaluation = SecurityScanner.evaluateEmail(senderChecks: sender, links: scannedLinks)
    }
}
