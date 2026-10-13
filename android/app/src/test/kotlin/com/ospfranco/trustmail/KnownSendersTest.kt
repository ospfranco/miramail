package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.KnownSenders
import com.ospfranco.trustmail.mail.MessageSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class KnownSendersTest {
    private fun summary(id: String, from: String, auth: List<String> = emptyList()) =
        MessageSummary(id, id, from, "s", "", false, authenticationResults = auth)

    @Test
    fun aSenderIsKnownOnlyThroughAnotherEmail() = runTest {
        val senders = KnownSenders()
        senders.record(listOf(summary("1", "Ann <Ann@X.com>")))
        assertTrue(senders.knows("ann@x.com", besides = "2"))
        assertFalse("an email doesn't make its own sender known", senders.knows("ann@x.com", besides = "1"))
        assertFalse(senders.knows("bob@x.com", besides = "2"))
        // The first email seen stays the one that doesn't count.
        senders.record(listOf(summary("2", "ann@x.com")))
        assertTrue(senders.knows("ann@x.com", besides = "2"))
    }

    @Test
    fun sendersThatFailedTheChecksAreNotRemembered() = runTest {
        val senders = KnownSenders()
        senders.record(listOf(summary("1", "ceo@x.com", listOf("mx.google.com; dmarc=fail header.from=x.com"))))
        assertFalse(senders.knows("ceo@x.com", besides = "2"))
    }

    @Test
    fun forgetAndClear() = runTest {
        val senders = KnownSenders()
        senders.record(listOf(summary("1", "a@x.com"), summary("2", "b@x.com")))
        senders.forget("a@x.com")
        assertFalse(senders.knows("a@x.com", besides = "9"))
        assertTrue(senders.knows("b@x.com", besides = "9"))
        senders.clear()
        assertFalse(senders.knows("b@x.com", besides = "9"))
    }

    @Test
    fun keptInAFileUntilCleared() = runTest {
        val directory = createTempDirectory().toFile()
        try {
            KnownSenders(directory).record(listOf(summary("1", "a@x.com")))
            assertTrue(KnownSenders(directory).knows("a@x.com", besides = "2"))
            KnownSenders(directory).clear()
            assertFalse(KnownSenders(directory).knows("a@x.com", besides = "2"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
