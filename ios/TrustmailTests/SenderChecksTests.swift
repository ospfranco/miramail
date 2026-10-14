import Testing
@testable import Trustmail

struct SenderChecksTests {
    private func check(_ results: String..., address: String = "noreply@github.com") -> SecurityCheck {
        SenderChecks.checkSenderDomain(makeMessage(address: address, auth: results))
    }

    @Test func inboxSummaryKnowsWhenTheSenderFailed() {
        func summary(_ auth: [String]) -> MessageSummary {
            MessageSummary(id: "1", threadId: "t", from: "PayPal <support@paypa1.example.net>", subject: "s", snippet: "", unread: false, authenticationResults: auth)
        }
        #expect(summary(["mx.google.com; dmarc=fail header.from=paypa1.example.net"]).senderFailed)
        #expect(!summary(["mx.google.com; dmarc=pass header.from=paypa1.example.net"]).senderFailed)
        #expect(!summary([]).senderFailed, "unverified is a warning, not a failure")
    }

    @Test func parsesAuthenticationResults() {
        let parsed = SenderChecks.parseAuthenticationResults(
            "mx.google.com; dkim=pass (test (nested) comment) header.i=@github.com header.s=pf2023; spf=pass smtp.mailfrom=bounce@github.com; dmarc=pass (p=REJECT) header.from=github.com"
        )
        #expect(parsed.map(\.method) == ["dkim", "spf", "dmarc"])
        #expect(parsed.map(\.result) == ["pass", "pass", "pass"])
        #expect(parsed[0].properties["header.i"] == "@github.com")
        #expect(parsed[1].properties["smtp.mailfrom"] == "bounce@github.com")
        #expect(SenderChecks.parseAuthenticationResults("mx.google.com; none").isEmpty)
        #expect(SenderChecks.parseAuthenticationResults("mx.google.com").isEmpty)
    }

    @Test func noResultsMeansUnverified() {
        #expect(check() == SecurityCheck(.notVerified))
        #expect(check().verdict == .warning)
    }

    @Test func dmarcPassIsVerified() {
        #expect(check("mx.google.com; dkim=pass header.i=@github.com; dmarc=pass header.from=github.com") == SecurityCheck(.verified))
    }

    @Test func dmarcFailIsDangerous() {
        let result = check("mx.google.com; spf=pass smtp.mailfrom=x@github.com; dmarc=fail header.from=github.com")
        #expect(result == SecurityCheck(.failed))
        #expect(result.verdict == .danger)
    }

    @Test func alignedDkimWithoutDmarcIsVerified() {
        #expect(check("mx.google.com; dkim=pass header.i=@mail.github.com; dmarc=none", address: "a@github.com") == SecurityCheck(.verified))
    }

    @Test func alignedSpfWithoutDmarcIsVerified() {
        #expect(check("mx.google.com; spf=pass smtp.mailfrom=bounces@mail.github.com", address: "a@github.com") == SecurityCheck(.verified))
    }

    @Test func failedSpfOrDkimWithoutDmarcIsDangerous() {
        #expect(check("mx.google.com; spf=fail smtp.mailfrom=x@github.com", address: "a@github.com") == SecurityCheck(.failed))
    }

    @Test func passThroughARelayIsOnlyAWarning() {
        let result = check("mx.google.com; dkim=pass header.d=sendgrid.net; spf=pass smtp.mailfrom=bounce@sendgrid.net", address: "a@github.com")
        #expect(result == SecurityCheck(.notVerified))
    }

    @Test func inconclusiveResultsAreUnverified() {
        #expect(check("mx.google.com; spf=softfail smtp.mailfrom=x@evil.com", address: "a@github.com") == SecurityCheck(.notVerified))
    }

    @Test func senderHistory() async {
        let message = makeMessage()
        #expect(await SenderChecks.checkSenderHistory(message, mail: FakeMail(known: true)) == SecurityCheck(.known))
        let unknown = await SenderChecks.checkSenderHistory(message, mail: FakeMail(known: false))
        #expect(unknown == SecurityCheck(.firstMessage))
        #expect(unknown.verdict == .warning)
        #expect(await SenderChecks.checkSenderHistory(message, mail: FakeMail(failsHistory: true)) == SecurityCheck(.historyUnknown))
    }

    @Test func sendersSeenBeforeStayKnownAfterTheirEmailIsDeleted() async {
        let senders = KnownSenders()
        senders.record([MessageSummary(id: "old", threadId: "old", from: "GitHub <noreply@github.com>", subject: "s", snippet: "", unread: false)])
        // Gmail no longer finds the old email, but the device remembers it.
        let known = await SenderChecks.checkSenderHistory(makeMessage(), mail: FakeMail(known: false), senders: senders)
        #expect(known == SecurityCheck(.known))
        let first = await SenderChecks.checkSenderHistory(makeMessage(id: "old"), mail: FakeMail(known: false), senders: senders)
        #expect(first == SecurityCheck(.firstMessage))
    }

    @Test func checkSenderReturnsDomainThenHistory() async {
        let checks = await SenderChecks.checkSender(makeMessage(), mail: FakeMail(known: true))
        #expect(checks == [SecurityCheck(.notVerified), SecurityCheck(.known)])
    }
}
