import Testing
@testable import Safemail

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
