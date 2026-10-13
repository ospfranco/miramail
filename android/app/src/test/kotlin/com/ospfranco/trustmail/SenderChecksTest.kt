package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.KnownSenders
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.security.SecurityCheck
import com.ospfranco.trustmail.security.SenderCheckLabel
import com.ospfranco.trustmail.security.SenderChecks
import com.ospfranco.trustmail.security.Verdict
import com.ospfranco.trustmail.security.senderFailed
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SenderChecksTest {
    @get:Rule val psl = PublicSuffixRule()

    private fun check(vararg results: String, address: String = "noreply@github.com") =
        SenderChecks.checkSenderDomain(makeMessage(address = address, auth = results.toList()))

    @Test
    fun inboxSummaryKnowsWhenTheSenderFailed() {
        fun summary(auth: List<String>) =
            MessageSummary("1", "t", "PayPal <support@paypa1.example.net>", "s", "", false, authenticationResults = auth)
        assertTrue(summary(listOf("mx.google.com; dmarc=fail header.from=paypa1.example.net")).senderFailed)
        assertFalse(summary(listOf("mx.google.com; dmarc=pass header.from=paypa1.example.net")).senderFailed)
        assertFalse("unverified is a warning, not a failure", summary(emptyList()).senderFailed)
    }

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
        assertEquals(SenderCheckLabel.NotVerified.check, check())
        assertEquals(Verdict.Warning, check().verdict)
    }

    @Test
    fun dmarcPassIsVerified() {
        assertEquals(SenderCheckLabel.Verified.check, check("mx.google.com; dkim=pass header.i=@github.com; dmarc=pass header.from=github.com"))
    }

    @Test
    fun dmarcFailIsDangerous() {
        val result = check("mx.google.com; spf=pass smtp.mailfrom=x@github.com; dmarc=fail header.from=github.com")
        assertEquals(SenderCheckLabel.Failed.check, result)
        assertEquals(Verdict.Danger, result.verdict)
    }

    @Test
    fun alignedDkimWithoutDmarcIsVerified() {
        assertEquals(SenderCheckLabel.Verified.check, check("mx.google.com; dkim=pass header.i=@mail.github.com; dmarc=none", address = "a@github.com"))
    }

    @Test
    fun alignedSpfWithoutDmarcIsVerified() {
        assertEquals(SenderCheckLabel.Verified.check, check("mx.google.com; spf=pass smtp.mailfrom=bounces@mail.github.com", address = "a@github.com"))
    }

    @Test
    fun failedSpfOrDkimWithoutDmarcIsDangerous() {
        assertEquals(SenderCheckLabel.Failed.check, check("mx.google.com; spf=fail smtp.mailfrom=x@github.com", address = "a@github.com"))
    }

    @Test
    fun passThroughARelayIsOnlyAWarning() {
        val result = check("mx.google.com; dkim=pass header.d=sendgrid.net; spf=pass smtp.mailfrom=bounce@sendgrid.net", address = "a@github.com")
        assertEquals(SenderCheckLabel.NotVerified.check, result)
    }

    @Test
    fun inconclusiveResultsAreUnverified() {
        assertEquals(SenderCheckLabel.NotVerified.check, check("mx.google.com; spf=softfail smtp.mailfrom=x@evil.com", address = "a@github.com"))
    }

    @Test
    fun senderHistory() = runTest {
        val message = makeMessage()
        assertEquals(SenderCheckLabel.Known.check, SenderChecks.checkSenderHistory(message, FakeMail(known = true)))
        val unknown = SenderChecks.checkSenderHistory(message, FakeMail(known = false))
        assertEquals(SenderCheckLabel.FirstMessage.check, unknown)
        assertEquals(Verdict.Warning, unknown.verdict)
        assertEquals(SenderCheckLabel.HistoryUnknown.check, SenderChecks.checkSenderHistory(message, FakeMail(failsHistory = true)))
    }

    @Test
    fun sendersSeenBeforeStayKnownAfterTheirEmailIsDeleted() = runTest {
        val senders = KnownSenders()
        senders.record(listOf(MessageSummary("old", "old", "GitHub <noreply@github.com>", "s", "", false)))
        // Gmail no longer finds the old email, but the device remembers it.
        val known = SenderChecks.checkSenderHistory(makeMessage(), FakeMail(known = false), senders)
        assertEquals("Known sender", known.label)
        val first = SenderChecks.checkSenderHistory(makeMessage(id = "old"), FakeMail(known = false), senders)
        assertEquals("First message from this sender", first.label)
    }

    @Test
    fun checkSenderReturnsDomainThenHistory() = runTest {
        val checks = SenderChecks.checkSender(makeMessage(), FakeMail(known = true))
        assertEquals(listOf("Sender not verified", "Known sender"), checks.map { it.label })
    }
}
