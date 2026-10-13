import Foundation
import Testing
@testable import Trustmail

struct KnownSendersTests {
    private func summary(_ id: String, from: String, auth: [String] = []) -> MessageSummary {
        MessageSummary(id: id, threadId: id, from: from, subject: "s", snippet: "", unread: false, authenticationResults: auth)
    }

    @Test func aSenderIsKnownOnlyThroughAnotherEmail() {
        let senders = KnownSenders()
        senders.record([summary("1", from: "Ann <Ann@X.com>")])
        #expect(senders.knows("ann@x.com", besides: "2"))
        #expect(!senders.knows("ann@x.com", besides: "1"), "an email doesn't make its own sender known")
        #expect(!senders.knows("bob@x.com", besides: "2"))
        // The first email seen stays the one that doesn't count.
        senders.record([summary("2", from: "ann@x.com")])
        #expect(senders.knows("ann@x.com", besides: "2"))
    }

    @Test func sendersThatFailedTheChecksAreNotRemembered() {
        let senders = KnownSenders()
        senders.record([summary("1", from: "ceo@x.com", auth: ["mx.google.com; dmarc=fail header.from=x.com"])])
        #expect(!senders.knows("ceo@x.com", besides: "2"))
    }

    @Test func forgetAndClear() {
        let senders = KnownSenders()
        senders.record([summary("1", from: "a@x.com"), summary("2", from: "b@x.com")])
        senders.forget("a@x.com")
        #expect(!senders.knows("a@x.com", besides: "9"))
        #expect(senders.knows("b@x.com", besides: "9"))
        senders.clear()
        #expect(!senders.knows("b@x.com", besides: "9"))
    }

    @Test func keptInAFileUntilCleared() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        KnownSenders(directory: directory).record([summary("1", from: "a@x.com")])
        #expect(KnownSenders(directory: directory).knows("a@x.com", besides: "2"))
        KnownSenders(directory: directory).clear()
        #expect(!KnownSenders(directory: directory).knows("a@x.com", besides: "2"))
    }
}
