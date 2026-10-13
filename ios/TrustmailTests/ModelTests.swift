import Testing
@testable import Trustmail

/// Auth with canned answers.
struct FakeAuth: AuthService {
    var restored: UserProfile?
    var signedIn: UserProfile?
    var restoreFails = false

    func restoreSession() async throws -> UserProfile? {
        if restoreFails { throw GmailError(status: 500, body: "no keychain") }
        return restored
    }
    func signIn() async throws -> UserProfile? { signedIn }
    func signOut() async {}
}

@MainActor
struct ModelTests {
    @Test func signedOutStartShowsSignIn() async {
        let session = SessionModel(services: Services(auth: FakeAuth(), mail: FakeMail()))
        #expect(session.isLoading)
        await session.start()
        #expect(session.user == nil)
        #expect(!session.isLoading)
        #expect(session.error == nil)
    }

    @Test func restoredSessionLoadsInbox() async {
        let session = SessionModel(services: Services(auth: FakeAuth(restored: UserProfile(email: "me@x.com")), mail: DemoInbox()))
        await session.start()
        #expect(session.user == UserProfile(email: "me@x.com"))
        #expect(session.messages.map(\.id) == ["1"])
        #expect(!session.isLoading)
    }

    @Test func inboxMarksEmailsFromNewSenders() async {
        let session = SessionModel(services: Services(auth: FakeAuth(restored: UserProfile(email: "me@x.com")), mail: HistoryInbox()))
        await session.start()
        await session.loadSenderHistory()
        // Only the sender you've never written with; a lookup that fails marks nothing.
        #expect(session.firstMessages == ["2"])
        // Inbox senders are remembered, so a later email from them is known even once this one is deleted.
        #expect(session.services.senders.knows("new@x.com", besides: "4"))
        await session.signOut()
        #expect(session.firstMessages.isEmpty)
        #expect(!session.services.senders.knows("new@x.com", besides: "4"))
    }

    @Test func movingToSpamForgetsTheSender() async {
        let services = Services(auth: FakeAuth(), mail: FakeMail())
        services.senders.record([MessageSummary(id: "old", threadId: "old", from: "noreply@github.com", subject: "s", snippet: "", unread: false)])
        let model = MessageModel(id: "m1", services: services)
        await model.load()
        #expect(await model.perform(.move(MailLabel(id: "work", name: "Work"))))
        #expect(services.senders.knows("noreply@github.com", besides: "m1"))
        #expect(await model.perform(.move(.spam)))
        #expect(!services.senders.knows("noreply@github.com", besides: "m1"))
    }

    @Test func restoreFailureIsShown() async {
        let session = SessionModel(services: Services(auth: FakeAuth(restoreFails: true), mail: FakeMail()))
        await session.start()
        #expect(session.user == nil)
        #expect(session.error?.contains("no keychain") == true)
    }

    @Test func signInThenSignOut() async {
        let session = SessionModel(services: Services(auth: FakeAuth(signedIn: UserProfile(email: "me@x.com")), mail: DemoInbox()))
        await session.start()
        await session.signIn()
        #expect(session.user?.email == "me@x.com")
        #expect(session.messages.count == 1)
        await session.signOut()
        #expect(session.user == nil)
        #expect(session.messages.isEmpty)
    }

    @Test func cancelledSignInStaysSignedOut() async {
        let session = SessionModel(services: Services(auth: FakeAuth(), mail: FakeMail()))
        await session.signIn()
        #expect(session.user == nil)
        #expect(session.error == nil)
    }

    @Test func messageModelScansLinksAndEvaluates() async {
        let model = MessageModel(id: "m1", services: Services(auth: FakeAuth(), mail: FakeMail(known: true)))
        await model.load()
        #expect(model.message?.id == "m1")
        #expect(model.evaluation == nil)

        await model.linksFound([FoundLink(url: "mailto:a@b.com", text: "mail"), FoundLink(url: "http://10.0.0.1/", text: "ip")])
        #expect(model.links?.count == 2)
        #expect(model.evaluation?.verdict == .danger)
        #expect(model.evaluation?.checks.map(\.label) == ["Sender not verified", "Known sender", "2 links scanned"])
    }

    @Test func messageModelIgnoresASecondScan() async {
        let model = MessageModel(id: "m1", services: Services(auth: FakeAuth(), mail: FakeMail()))
        await model.load()
        await model.linksFound([])
        await model.linksFound([FoundLink(url: "http://10.0.0.1/", text: "")])
        #expect(model.links?.isEmpty == true)
    }
}

private struct DemoInbox: MailService {
    func listInbox(maxResults: Int) async throws -> [MessageSummary] {
        [MessageSummary(id: "1", threadId: "t", from: "a@x.com", subject: "s", snippet: "", unread: false)]
    }
    func getMessage(id: String) async throws -> MessageDetail { makeMessage(id: id) }
    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool { false }
    func trash(id: String) async throws {}
    func archive(id: String) async throws {}
    func move(id: String, to label: MailLabel) async throws {}
    func moveTargets() async throws -> [MailLabel] { [] }
    func send(_ message: OutgoingMessage) async throws {}
}

/// An inbox with a known sender, a new one, and one whose history lookup fails.
private struct HistoryInbox: MailService {
    func listInbox(maxResults: Int) async throws -> [MessageSummary] {
        [("1", "Known <known@x.com>"), ("2", "New <new@x.com>"), ("3", "broken@x.com")].map {
            MessageSummary(id: $0.0, threadId: $0.0, from: $0.1, subject: "s", snippet: "", unread: false)
        }
    }
    func getMessage(id: String) async throws -> MessageDetail { makeMessage(id: id) }
    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool {
        if address == "broken@x.com" { throw GmailError(status: 500, body: "boom") }
        return address == "known@x.com"
    }
    func trash(id: String) async throws {}
    func archive(id: String) async throws {}
    func move(id: String, to label: MailLabel) async throws {}
    func moveTargets() async throws -> [MailLabel] { [] }
    func send(_ message: OutgoingMessage) async throws {}
}
