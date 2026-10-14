import Foundation
import Testing
@testable import Trustmail

struct MimeTests {
    private func raw(_ to: String, _ subject: String, _ body: String, inReplyTo: String? = nil, references: String? = nil) -> String {
        Mime.raw(OutgoingMessage(to: to, subject: subject, body: body, inReplyTo: inReplyTo, references: references))
    }

    private let tail = "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"

    // Expected strings were produced independently with Python's base64 module.
    @Test func buildsASimpleMessage() {
        #expect(raw("bob@example.com", "Hello", "Hi Bob\nSecond line")
            == "To: bob@example.com\r\nSubject: Hello\r\n\(tail)SGkgQm9iDQpTZWNvbmQgbGluZQ==")
    }

    @Test func addsThreadingHeadersForAReply() {
        #expect(raw("ann@example.com", "Re: Report", "Thanks!", inReplyTo: "<m2@x.com>", references: "<m1@x.com> <m2@x.com>")
            == "To: ann@example.com\r\nSubject: Re: Report\r\nIn-Reply-To: <m2@x.com>\r\nReferences: <m1@x.com> <m2@x.com>\r\n\(tail)VGhhbmtzIQ==")
    }

    @Test func encodesNonAsciiSubjectsAndBodies() {
        #expect(raw("bob@example.com", "Café ☕", "Olá ✓")
            == "To: bob@example.com\r\nSubject: =?UTF-8?B?Q2Fmw6kg4piV?=\r\n\(tail)T2zDoSDinJM=")
    }

    @Test func stripsLineBreaksSoHeadersCannotBeInjected() {
        #expect(raw("a@b.com", "Hi\r\nBcc: evil@x.com", "x") == "To: a@b.com\r\nSubject: HiBcc: evil@x.com\r\n\(tail)eA==")
        #expect(!raw("a@b.com\r\nBcc: evil@x.com", "s", "x").contains("\r\nBcc:"))
    }

    @Test func wrapsTheBodyAt76Characters() {
        #expect(raw("a@b.com", "Long", String(repeating: "a", count: 100))
            == "To: a@b.com\r\nSubject: Long\r\n\(tail)YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh\r\nYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==")
    }

    @Test func keepsEveryRecipient() {
        #expect(raw("a@b.com, Bob <b@c.org>", "Two", "x") == "To: a@b.com, Bob <b@c.org>\r\nSubject: Two\r\n\(tail)eA==")
        #expect(raw("a@b.com; c@d.com", "Two", "x").hasPrefix("To: a@b.com, c@d.com\r\n"))
    }

    @Test func validatesRecipients() {
        for good in ["bob@example.com", "Bob <bob@example.com>", "a@b.com, c@d.org", "a@b.com; c@d.org", "  a@b.com  ", "first.last+tag@sub.example.co.uk"] {
            #expect(Mime.isValidRecipientList(good), "\(good)")
        }
        for bad in ["", "   ", "bob", "bob@", "bob@example", "@example.com", "a@b.com, nope", "a b@c.com", "a@@b.com"] {
            #expect(!Mime.isValidRecipientList(bad), "\(bad)")
        }
    }
}

struct ReplyTests {
    private func message(subject: String = "Report", replyTo: String = "", references: String = "<m1@x.com>") -> MessageDetail {
        MessageDetail(
            id: "m2", from: "Ann <ANN@Example.com>", fromAddress: "ann@example.com", to: "me@x.com", subject: subject,
            date: "", body: "", bodyType: .text, authenticationResults: [],
            threadId: "t1", messageId: "<m2@x.com>", references: references, replyTo: replyTo
        )
    }

    @Test func repliesToTheSenderInTheSameConversation() {
        #expect(message().makeReply() == OutgoingMessage(
            to: "ann@example.com", subject: "Re: Report", body: "",
            threadId: "t1", inReplyTo: "<m2@x.com>", references: "<m1@x.com> <m2@x.com>"
        ))
    }

    @Test func doesNotStackRePrefixes() {
        #expect(message(subject: "Re: Report").makeReply().subject == "Re: Report")
        #expect(message(subject: "RE: Report").makeReply().subject == "RE: Report")
        #expect(message(subject: "Reminder").makeReply().subject == "Re: Reminder")
    }

    @Test func prefersTheReplyToAddress() {
        #expect(message(replyTo: "Support <Help@Example.com>").makeReply().to == "help@example.com")
        #expect(message(replyTo: "   ").makeReply().to == "ann@example.com")
    }

    @Test func handlesMissingSubjectAndReferences() {
        let reply = message(subject: "", references: "").makeReply()
        #expect(reply.subject == "Re: ")
        #expect(reply.references == "<m2@x.com>")
        let bare = MessageDetail(id: "x", from: "a@b.com", fromAddress: "a@b.com", to: "", subject: "S", date: "", body: "", bodyType: .text, authenticationResults: [])
        #expect(bare.makeReply().threadId == nil)
        #expect(bare.makeReply().inReplyTo == nil)
        #expect(bare.makeReply().references == nil)
    }
}

/// The request bodies Gmail receives, read back from the stub.
extension StubbedNetworkTests {
@Suite struct GmailActionTests {
    private final class Seen: @unchecked Sendable {
        private let lock = NSLock()
        private var list: [(method: String, url: String, body: [String: Any])] = []
        func add(_ method: String, _ url: String, _ body: [String: Any]) { lock.withLock { list.append((method, url, body)) } }
        var all: [(method: String, url: String, body: [String: Any])] { lock.withLock { list } }
    }

    private func bodyJSON(_ request: URLRequest) -> [String: Any] {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                data.append(buffer, count: count)
            }
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func client(seen: Seen, response: @escaping @Sendable (URLRequest) -> (Int, String) = { _ in (200, "{}") }) -> GmailClient {
        StubProtocol.set { request in
            seen.add(request.httpMethod ?? "", request.url?.absoluteString ?? "", self.bodyJSON(request))
            return response(request)
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return GmailClient(session: URLSession(configuration: config)) { _ in "token" }
    }

    private let base = "https://gmail.googleapis.com/gmail/v1/users/me"

    @Test func trashPostsToTheTrashEndpoint() async throws {
        let seen = Seen()
        try await client(seen: seen).trash(id: "m1")
        #expect(seen.all.map(\.method) == ["POST"])
        #expect(seen.all.map(\.url) == ["\(base)/messages/m1/trash"])
    }

    @Test func archiveRemovesTheInboxLabel() async throws {
        let seen = Seen()
        try await client(seen: seen).archive(id: "m1")
        #expect(seen.all[0].url == "\(base)/messages/m1/modify")
        #expect(seen.all[0].body["removeLabelIds"] as? [String] == ["INBOX"])
        #expect(seen.all[0].body["addLabelIds"] == nil)
    }

    @Test func moveAddsTheLabelAndRemovesTheInboxLabel() async throws {
        let seen = Seen()
        try await client(seen: seen).move(id: "m1", to: MailLabel(id: "Label_7", name: "Work"))
        #expect(seen.all[0].url == "\(base)/messages/m1/modify")
        #expect(seen.all[0].body["addLabelIds"] as? [String] == ["Label_7"])
        #expect(seen.all[0].body["removeLabelIds"] as? [String] == ["INBOX"])
    }

    @Test func moveTargetsAreTheUsersLabelsSortedThenSpam() async throws {
        let json = #"{"labels":[{"id":"INBOX","name":"INBOX","type":"system"},{"id":"L2","name":"work","type":"user"},{"id":"L1","name":"Receipts","type":"user"},{"id":"SENT","name":"SENT","type":"system"}]}"#
        let targets = try await client(seen: Seen(), response: { _ in (200, json) }).moveTargets()
        #expect(targets == [MailLabel(id: "L1", name: "Receipts"), MailLabel(id: "L2", name: "work"), MailLabel(id: "SPAM", name: "Spam")])
        let none = try await client(seen: Seen(), response: { _ in (200, "{}") }).moveTargets()
        #expect(none == [MailLabel(id: "SPAM", name: "Spam")])
    }

    @Test func sendPostsTheRawMessageInItsThread() async throws {
        let seen = Seen()
        let message = OutgoingMessage(to: "ann@example.com", subject: "Re: Report", body: "Thanks!", threadId: "t1", inReplyTo: "<m2@x.com>", references: "<m1@x.com> <m2@x.com>")
        try await client(seen: seen).send(message)
        #expect(seen.all[0].method == "POST")
        #expect(seen.all[0].url == "\(base)/messages/send")
        #expect(seen.all[0].body["threadId"] as? String == "t1")
        let raw = try #require(seen.all[0].body["raw"] as? String)
        #expect(!raw.contains("=") && !raw.contains("+") && !raw.contains("/"))
        #expect(utf8Decode(base64ToBytes(raw)) == Mime.raw(message))
    }

    @Test func sendWithoutAThreadOmitsIt() async throws {
        let seen = Seen()
        try await client(seen: seen).send(OutgoingMessage(to: "a@b.com", subject: "Hi", body: "x"))
        #expect(seen.all[0].body["threadId"] == nil)
    }

    @Test func actionErrorsSurface() async {
        await #expect(throws: GmailError.self) {
            try await client(seen: Seen(), response: { _ in (403, "denied") }).archive(id: "m1")
        }
    }

    @Test func gettingAMessageKeepsWhatAReplyNeeds() async throws {
        let json = #"{"id":"m9","threadId":"t9","snippet":"s","payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"Ann <a@x.com>"},{"name":"Message-Id","value":"<m9@x.com>"},{"name":"References","value":"<m8@x.com>"},{"name":"Reply-To","value":"help@x.com"}],"body":{"size":1,"data":"eA"}}}"#
        let message = try await client(seen: Seen(), response: { _ in (200, json) }).getMessage(id: "m9")
        #expect(message.threadId == "t9")
        #expect(message.messageId == "<m9@x.com>")
        #expect(message.references == "<m8@x.com>")
        #expect(message.replyTo == "help@x.com")
        #expect(message.makeReply().to == "help@x.com")
    }
}

}

@MainActor
struct MailActionModelTests {
    private func services(_ mail: FakeMail) -> Services { Services(auth: FakeAuth(), mail: mail) }

    @Test func noticeIsShownThenClears() async throws {
        let session = SessionModel(services: services(FakeMail()), noticeDuration: .milliseconds(60))
        session.showNotice("Archived")
        #expect(session.notice == "Archived")
        try await Task.sleep(for: .milliseconds(400))
        #expect(session.notice == nil)
    }

    @Test func aNewerNoticeReplacesTheOldOneAndRestartsTheClock() async throws {
        let session = SessionModel(services: services(FakeMail()), noticeDuration: .milliseconds(300))
        session.showNotice("First")
        try await Task.sleep(for: .milliseconds(200))
        session.showNotice("Second")
        try await Task.sleep(for: .milliseconds(200)) // 400ms after the first, but only 200 after the second
        #expect(session.notice == "Second")
    }

    @Test func removingAMessageDropsItFromTheInboxAndShowsANotice() async {
        let mail = DemoInboxMail()
        let session = SessionModel(services: Services(auth: FakeAuth(restored: UserProfile(email: "me@x.com")), mail: mail))
        await session.start()
        #expect(session.messages.map(\.id) == ["1", "2"])
        session.removeMessage("1", notice: "Archived")
        #expect(session.messages.map(\.id) == ["2"])
        #expect(session.notice == "Archived")
    }

    @Test func actionsCallTheMailServiceAndReportSuccess() async {
        let mail = FakeMail()
        let model = MessageModel(id: "m1", services: services(mail))
        #expect(await model.perform(.archive))
        #expect(await model.perform(.trash))
        #expect(await model.perform(.move(MailLabel(id: "work", name: "Work"))))
        #expect(mail.calls == ["archive m1", "trash m1", "move m1 work"])
        #expect(!model.isBusy)
    }

    @Test func aFailedActionReportsFailure() async {
        let model = MessageModel(id: "m1", services: services(FakeMail(failsActions: true)))
        #expect(await model.perform(.archive) == false)
        #expect(!model.isBusy)
    }

    @Test func actionNotices() {
        #expect(MessageAction.archive.notice == "Archived")
        #expect(MessageAction.trash.notice == "Moved to Trash")
        #expect(MessageAction.move(MailLabel(id: "w", name: "Work")).notice == "Moved to Work")
        #expect(MessageAction.archive.failureNotice == "Couldn't archive the email")
        #expect(MessageAction.trash.failureNotice == "Couldn't delete the email")
        #expect(MessageAction.move(MailLabel(id: "w", name: "Work")).failureNotice == "Couldn't move the email")
    }

    @Test func moveTargetsComeFromTheService() async throws {
        let model = MessageModel(id: "m1", services: services(FakeMail()))
        #expect(try await model.loadMoveTargets().map(\.name) == ["Work", "Spam"])
    }

    @Test func theReplyIsPrefilledFromTheLoadedMessage() async {
        let model = MessageModel(id: "m1", services: services(FakeMail()))
        #expect(model.reply == nil)
        await model.load()
        #expect(model.reply?.to == "noreply@github.com")
        #expect(model.reply?.subject == "Re: Hi")
    }

    @Test func composeCanSendOnlyToValidRecipients() {
        let model = ComposeModel(draft: OutgoingMessage(to: "", subject: "", body: ""), services: services(FakeMail()))
        #expect(!model.canSend)
        model.to = "nope"
        #expect(!model.canSend)
        model.to = "bob@example.com"
        #expect(model.canSend)
    }

    @Test func composeSendsTheEditedMessageInTheOriginalThread() async {
        let mail = FakeMail()
        let draft = OutgoingMessage(to: "ann@example.com", subject: "Re: Report", body: "", threadId: "t1", inReplyTo: "<m2@x.com>", references: "<m1@x.com> <m2@x.com>")
        let model = ComposeModel(draft: draft, services: services(mail))
        model.to = "ann@example.com, bob@example.com"
        model.body = "Thanks!"
        #expect(await model.send())
        #expect(mail.sent == [OutgoingMessage(to: "ann@example.com, bob@example.com", subject: "Re: Report", body: "Thanks!", threadId: "t1", inReplyTo: "<m2@x.com>", references: "<m1@x.com> <m2@x.com>")])
        #expect(model.error == nil)
        #expect(!model.isSending)
    }

    @Test func composeDoesNotSendWithoutAValidRecipient() async {
        let mail = FakeMail()
        let model = ComposeModel(draft: OutgoingMessage(to: "bad", subject: "s", body: "b"), services: services(mail))
        #expect(await model.send() == false)
        #expect(mail.calls.isEmpty)
    }

    @Test func aFailedSendKeepsTheDraftAndShowsTheError() async {
        let model = ComposeModel(draft: OutgoingMessage(to: "a@b.com", subject: "s", body: "b"), services: services(FakeMail(failsActions: true)))
        #expect(await model.send() == false)
        #expect(model.error?.contains("boom") == true)
        #expect(model.body == "b")
        #expect(!model.isSending)
    }
}

/// Two inbox messages, for tests that remove one.
private struct DemoInboxMail: MailService {
    func listInbox(maxResults: Int) async throws -> [MessageSummary] {
        ["1", "2"].map { MessageSummary(id: $0, threadId: $0, from: "a@x.com", subject: "s\($0)", snippet: "", unread: false) }
    }
    func getMessage(id: String) async throws -> MessageDetail { makeMessage(id: id) }
    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool { false }
    func trash(id: String) async throws {}
    func archive(id: String) async throws {}
    func move(id: String, to label: MailLabel) async throws {}
    func moveTargets() async throws -> [MailLabel] { [] }
    func send(_ message: OutgoingMessage) async throws {}
}
