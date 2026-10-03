import Testing
@testable import Safemail

struct SenderChecksTests {
    private func check(_ results: String..., address: String = "noreply@github.com") -> SecurityCheck {
        SenderChecks.checkSenderDomain(makeMessage(address: address, auth: results))
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
        let result = check()
        #expect(result.verdict == .warning)
        #expect(result.label == "Sender not verified")
        #expect(result.detail == "Gmail recorded no authentication results, so github.com can't be confirmed as the real sender.")
    }

    @Test func dmarcPassIsVerified() {
        let result = check("mx.google.com; dkim=pass header.i=@github.com; dmarc=pass header.from=github.com")
        #expect(result == SecurityCheck(label: "Sender verified", detail: "Gmail confirmed this email really comes from github.com (DMARC).", verdict: .safe))
    }

    @Test func dmarcFailIsDangerous() {
        let result = check("mx.google.com; spf=pass smtp.mailfrom=x@github.com; dmarc=fail header.from=github.com")
        #expect(result.verdict == .danger)
        #expect(result.label == "Sender verification failed")
        #expect(result.detail == "github.com says it didn't send this email (DMARC failed). It may be forged.")
    }

    @Test func alignedDkimWithoutDmarcIsVerified() {
        let result = check("mx.google.com; dkim=pass header.i=@mail.github.com; dmarc=none", address: "a@github.com")
        #expect(result == SecurityCheck(label: "Sender verified", detail: "Gmail confirmed this email was signed by github.com (DKIM).", verdict: .safe))
    }

    @Test func alignedSpfWithoutDmarcIsVerified() {
        let result = check("mx.google.com; spf=pass smtp.mailfrom=bounces@mail.github.com", address: "a@github.com")
        #expect(result == SecurityCheck(label: "Sender verified", detail: "Gmail confirmed this email was sent by github.com (SPF).", verdict: .safe))
    }

    @Test func failedSpfOrDkimWithoutDmarcIsDangerous() {
        let result = check("mx.google.com; spf=fail smtp.mailfrom=x@github.com", address: "a@github.com")
        #expect(result.verdict == .danger)
        #expect(result.detail == "This email failed github.com's sender checks. It may be forged.")
    }

    @Test func passThroughARelayIsOnlyAWarning() {
        let result = check("mx.google.com; dkim=pass header.d=sendgrid.net; spf=pass smtp.mailfrom=bounce@sendgrid.net", address: "a@github.com")
        #expect(result.verdict == .warning)
        #expect(result.detail == "Sent through sendgrid.net on behalf of github.com, but github.com itself isn't verified.")
    }

    @Test func inconclusiveResultsAreUnverified() {
        let result = check("mx.google.com; spf=softfail smtp.mailfrom=x@evil.com", address: "a@github.com")
        #expect(result == SecurityCheck(label: "Sender not verified", detail: "github.com couldn't be verified as the real sender.", verdict: .warning))
    }

    @Test func senderHistory() async {
        let message = makeMessage()
        let known = await SenderChecks.checkSenderHistory(message, mail: FakeMail(known: true))
        #expect(known == SecurityCheck(label: "Known sender", detail: "You've exchanged email with this address before.", verdict: .safe))
        let unknown = await SenderChecks.checkSenderHistory(message, mail: FakeMail(known: false))
        #expect(unknown.label == "First message from this sender")
        #expect(unknown.verdict == .warning)
        let failed = await SenderChecks.checkSenderHistory(message, mail: FakeMail(failsHistory: true))
        #expect(failed == SecurityCheck(label: "Sender history unknown", detail: "Couldn't search your past email.", verdict: .warning))
    }

    @Test func checkSenderReturnsDomainThenHistory() async {
        let checks = await SenderChecks.checkSender(makeMessage(), mail: FakeMail(known: true))
        #expect(checks.map(\.label) == ["Sender not verified", "Known sender"])
    }
}
