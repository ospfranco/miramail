package com.ospfranco.trustmail

import com.ospfranco.trustmail.security.SecurityCheck
import com.ospfranco.trustmail.security.SenderChecks
import com.ospfranco.trustmail.security.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SenderChecksTest {
    @get:Rule val psl = PublicSuffixRule()

    private fun check(vararg results: String, address: String = "noreply@github.com") =
        SenderChecks.checkSenderDomain(makeMessage(address = address, auth = results.toList()))

    @Test
    fun parsesAuthenticationResults() {
        val parsed = SenderChecks.parseAuthenticationResults(
            "mx.google.com; dkim=pass (test (nested) comment) header.i=@github.com header.s=pf2023; spf=pass smtp.mailfrom=bounce@github.com; dmarc=pass (p=REJECT) header.from=github.com",
        )
        assertEquals(listOf("dkim", "spf", "dmarc"), parsed.map { it.method })
        assertEquals(listOf("pass", "pass", "pass"), parsed.map { it.result })
        assertEquals("@github.com", parsed[0].properties["header.i"])
        assertEquals("bounce@github.com", parsed[1].properties["smtp.mailfrom"])
        assertTrue(SenderChecks.parseAuthenticationResults("mx.google.com; none").isEmpty())
        assertTrue(SenderChecks.parseAuthenticationResults("mx.google.com").isEmpty())
    }

    @Test
    fun noResultsMeansUnverified() {
        val result = check()
        assertEquals(Verdict.Warning, result.verdict)
        assertEquals("Sender not verified", result.label)
        assertEquals("Gmail recorded no authentication results, so github.com can't be confirmed as the real sender.", result.detail)
    }

    @Test
    fun dmarcPassIsVerified() {
        assertEquals(
            SecurityCheck("Sender verified", "Gmail confirmed this email really comes from github.com (DMARC).", Verdict.Safe),
            check("mx.google.com; dkim=pass header.i=@github.com; dmarc=pass header.from=github.com"),
        )
    }

    @Test
    fun dmarcFailIsDangerous() {
        val result = check("mx.google.com; spf=pass smtp.mailfrom=x@github.com; dmarc=fail header.from=github.com")
        assertEquals(Verdict.Danger, result.verdict)
        assertEquals("Sender verification failed", result.label)
        assertEquals("github.com says it didn't send this email (DMARC failed). It may be forged.", result.detail)
    }

    @Test
    fun alignedDkimWithoutDmarcIsVerified() {
        assertEquals(
            SecurityCheck("Sender verified", "Gmail confirmed this email was signed by github.com (DKIM).", Verdict.Safe),
            check("mx.google.com; dkim=pass header.i=@mail.github.com; dmarc=none", address = "a@github.com"),
        )
    }

    @Test
    fun alignedSpfWithoutDmarcIsVerified() {
        assertEquals(
            SecurityCheck("Sender verified", "Gmail confirmed this email was sent by github.com (SPF).", Verdict.Safe),
            check("mx.google.com; spf=pass smtp.mailfrom=bounces@mail.github.com", address = "a@github.com"),
        )
    }

    @Test
    fun failedSpfOrDkimWithoutDmarcIsDangerous() {
        val result = check("mx.google.com; spf=fail smtp.mailfrom=x@github.com", address = "a@github.com")
        assertEquals(Verdict.Danger, result.verdict)
        assertEquals("This email failed github.com's sender checks. It may be forged.", result.detail)
    }

    @Test
    fun passThroughARelayIsOnlyAWarning() {
        val result = check("mx.google.com; dkim=pass header.d=sendgrid.net; spf=pass smtp.mailfrom=bounce@sendgrid.net", address = "a@github.com")
        assertEquals(Verdict.Warning, result.verdict)
        assertEquals("Sent through sendgrid.net on behalf of github.com, but github.com itself isn't verified.", result.detail)
    }

    @Test
    fun inconclusiveResultsAreUnverified() {
        assertEquals(
            SecurityCheck("Sender not verified", "github.com couldn't be verified as the real sender.", Verdict.Warning),
            check("mx.google.com; spf=softfail smtp.mailfrom=x@evil.com", address = "a@github.com"),
        )
    }

    @Test
    fun senderHistory() = runTest {
        val message = makeMessage()
        assertEquals(
            SecurityCheck("Known sender", "You've exchanged email with this address before.", Verdict.Safe),
            SenderChecks.checkSenderHistory(message, FakeMail(known = true)),
        )
        val unknown = SenderChecks.checkSenderHistory(message, FakeMail(known = false))
        assertEquals("First message from this sender", unknown.label)
        assertEquals(Verdict.Warning, unknown.verdict)
        assertEquals(
            SecurityCheck("Sender history unknown", "Couldn't search your past email.", Verdict.Warning),
            SenderChecks.checkSenderHistory(message, FakeMail(failsHistory = true)),
        )
    }

    @Test
    fun checkSenderReturnsDomainThenHistory() = runTest {
        val checks = SenderChecks.checkSender(makeMessage(), FakeMail(known = true))
        assertEquals(listOf("Sender not verified", "Known sender"), checks.map { it.label })
    }
}
