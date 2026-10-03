import Foundation
@testable import Safemail

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

/// A mail service with canned answers, for tests that must not touch the network.
struct FakeMail: MailService {
    var known = false
    var failsHistory = false
    var message: MessageDetail = makeMessage()

    func listInbox(maxResults: Int) async throws -> [MessageSummary] { [] }
    func getMessage(id: String) async throws -> MessageDetail { message }
    func hasCorrespondence(with address: String, excluding messageId: String) async throws -> Bool {
        if failsHistory { throw GmailError(status: 500, body: "boom") }
        return known
    }
}
