import Foundation
import Testing
@testable import Trustmail

/// A Gmail that is either reachable (and has one new email) or offline.
private struct ReachableInbox: MailService {
    let offline: Bool

    func listInbox(maxResults: Int) async throws -> [MessageSummary] {
        if offline { throw URLError(.notConnectedToInternet) }
        return [MessageSummary(id: "new", threadId: "new", from: "a@x.com", subject: "s", snippet: "", unread: false)]
    }
    func getMessage(id: String) async throws -> MessageDetail { makeMessage(id: id) }
    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool { true }
    func trash(id: String) async throws {}
    func archive(id: String) async throws {}
    func move(id: String, to label: MailLabel) async throws {}
    func moveTargets() async throws -> [MailLabel] { [] }
    func send(_ message: OutgoingMessage) async throws {}
}

@MainActor
struct InboxSnapshotTests {
    private let me = UserProfile(email: "me@x.com")

    private func saved(account: String = "me@x.com") -> InboxSnapshot {
        InboxSnapshot(
            account: account,
            messages: [MessageSummary(id: "old", threadId: "old", from: "b@x.com", subject: "s", snippet: "", unread: true)],
            firstMessages: ["old"]
        )
    }

    private func session(_ store: InboxSnapshotStore, offline: Bool) -> SessionModel {
        SessionModel(services: Services(auth: FakeAuth(restored: me), mail: ReachableInbox(offline: offline)), snapshots: store)
    }

    @Test func showsTheSavedInboxWhenGmailCantBeReached() async {
        let session = session(MemoryInboxSnapshotStore(saved()), offline: true)
        await session.start()
        #expect(session.messages.map(\.id) == ["old"])
        #expect(session.firstMessages == ["old"])
        #expect(session.showsSavedInbox)
        #expect(session.error != nil)
    }

    @Test func aRefreshReplacesTheSavedInboxAndSavesTheNewOne() async {
        let store = MemoryInboxSnapshotStore(saved())
        let session = session(store, offline: false)
        await session.start()
        #expect(session.messages.map(\.id) == ["new"])
        #expect(!session.showsSavedInbox)
        #expect(store.load()?.messages.map(\.id) == ["new"])
        #expect(store.load()?.firstMessages.isEmpty == true, "marks for emails no longer in the inbox are dropped")

        session.removeMessage("new", notice: "Archived")
        #expect(store.load()?.messages.isEmpty == true)
    }

    @Test func anotherAccountsInboxIsNeverShown() async {
        let session = session(MemoryInboxSnapshotStore(saved(account: "other@x.com")), offline: true)
        await session.start()
        #expect(session.messages.isEmpty)
        #expect(!session.showsSavedInbox)
    }

    @Test func signingOutDeletesTheSavedInbox() async {
        let store = MemoryInboxSnapshotStore(saved())
        let session = session(store, offline: false)
        await session.start()
        await session.signOut()
        #expect(store.load() == nil)
        #expect(!session.showsSavedInbox)
    }

    @Test func fileStoreKeepsTheInboxAndIgnoresAGarbledFile() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FileInboxSnapshotStore(directory: directory)
        #expect(store.load() == nil)
        store.save(saved())
        #expect(FileInboxSnapshotStore(directory: directory).load() == saved())

        try Data("not json".utf8).write(to: directory.appending(path: "inbox-snapshot.json"))
        #expect(store.load() == nil)
        store.save(saved())
        store.clear()
        #expect(store.load() == nil)
    }

    /// Offline, the Google session is kept (so the saved inbox shows); any other failure is a real sign-in problem.
    @Test func tellsNetworkFailuresFromSignInFailures() {
        #expect(GoogleAuth.isNetworkError(URLError(.notConnectedToInternet)))
        let wrapped = NSError(domain: "com.google.GIDSignIn", code: -1, userInfo: [NSUnderlyingErrorKey: URLError(.timedOut) as NSError])
        #expect(GoogleAuth.isNetworkError(wrapped))
        #expect(GoogleAuth.isNetworkError(NSError(domain: "org.openid.appauth.general", code: -5)))
        #expect(!GoogleAuth.isNetworkError(NSError(domain: "org.openid.appauth.oauth_token", code: -10)))
        #expect(!GoogleAuth.isNetworkError(NSError(domain: "com.google.GIDSignIn", code: -4)))
    }
}
