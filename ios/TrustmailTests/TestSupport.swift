import Foundation
import Testing
@testable import Trustmail

/// Every suite that uses the shared URL stub lives under this one, so they never run in parallel.
@Suite(.serialized) enum StubbedNetworkTests {}

private final class BundleToken {}

enum Fixture {
    static func text(_ name: String, ext: String) -> String {
        let bundle = Bundle(for: BundleToken.self)
        let url = bundle.url(forResource: name, withExtension: ext)!
        return try! String(contentsOf: url, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

func date(_ iso: String) -> Date { ISO8601DateFormatter().date(from: iso)! }

func makeCertificate(
    der: String = "AAAA",
    notBefore: Date = Date(timeIntervalSince1970: 0),
    issuerOrg: String? = "Let's Encrypt",
    issuerCommonName: String? = "R11",
    subjectCommonName: String? = nil,
    dnsNames: [String] = []
) -> Certificate {
    Certificate(
        der: der,
        notBefore: notBefore,
        notAfter: notBefore.addingTimeInterval(90 * 86_400),
        issuerOrg: issuerOrg,
        issuerCommonName: issuerCommonName,
        subjectCommonName: subjectCommonName,
        dnsNames: dnsNames,
        ipAddresses: []
    )
}

func makeMessage(
    id: String = "m1",
    from: String = "GitHub <noreply@github.com>",
    address: String = "noreply@github.com",
    auth: [String] = []
) -> MessageDetail {
    MessageDetail(
        id: id, from: from, fromAddress: address, to: "me@example.com", subject: "Hi",
        date: "Fri, 2 Oct 2026 09:00:00 +0000", body: "body", bodyType: .text, authenticationResults: auth
    )
}

/// A mail service with canned answers that records what it is asked to do, for tests that must not touch the network.
final class FakeMail: MailService, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [String] = []
    private var sentMessages: [OutgoingMessage] = []
    let known: Bool
    let failsHistory: Bool
    let failsActions: Bool
    let message: MessageDetail

    init(known: Bool = false, failsHistory: Bool = false, failsActions: Bool = false, message: MessageDetail = makeMessage()) {
        self.known = known
        self.failsHistory = failsHistory
        self.failsActions = failsActions
        self.message = message
    }

    /// What was asked of the service, in order, e.g. `["archive m1", "move m1 work"]`.
    var calls: [String] { lock.withLock { recorded } }
    var sent: [OutgoingMessage] { lock.withLock { sentMessages } }

    private func record(_ call: String) throws {
        lock.withLock { recorded.append(call) }
        if failsActions { throw GmailError(status: 500, body: "boom") }
    }

    func listInbox(maxResults: Int) async throws -> [MessageSummary] { [] }
    func getMessage(id: String) async throws -> MessageDetail { message }
    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool {
        if failsHistory { throw GmailError(status: 500, body: "boom") }
        return known
    }
    func trash(id: String) async throws { try record("trash \(id)") }
    func archive(id: String) async throws { try record("archive \(id)") }
    func move(id: String, to label: MailLabel) async throws { try record("move \(id) \(label.id)") }
    func moveTargets() async throws -> [MailLabel] { [MailLabel(id: "work", name: "Work"), MailLabel(id: "SPAM", name: "Spam")] }
    func send(_ message: OutgoingMessage) async throws {
        try record("send")
        lock.withLock { sentMessages.append(message) }
    }
}
