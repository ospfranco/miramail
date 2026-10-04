package com.ospfranco.safemail

import com.ospfranco.safemail.mail.BodyType
import com.ospfranco.safemail.mail.GmailException
import com.ospfranco.safemail.mail.MailLabel
import com.ospfranco.safemail.mail.MailService
import com.ospfranco.safemail.mail.MessageDetail
import com.ospfranco.safemail.mail.MessageSummary
import com.ospfranco.safemail.mail.OutgoingMessage
import com.ospfranco.safemail.security.Certificate
import com.ospfranco.safemail.security.KnownDomains
import com.ospfranco.safemail.security.PublicSuffixList
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

object Fixture {
    fun text(name: String): String =
        checkNotNull(Fixture::class.java.classLoader.getResource(name)) { "missing fixture $name" }.readText().trim()
}

/** Loads the same Public Suffix List and known-domains list the app ships, since the app normally does it in `SafemailApplication`. */
class PublicSuffixRule : TestWatcher() {
    override fun starting(description: Description) {
        PublicSuffixList.shared = PublicSuffixList(File("src/main/assets/public_suffix_list.dat").readText())
        KnownDomains.shared = KnownDomains(File("src/main/assets/known_domains.txt").readText())
    }
}

fun date(iso: String): Instant = Instant.parse(iso)

fun makeCertificate(
    der: String = "AAAA",
    notBefore: Instant = Instant.EPOCH,
    issuerOrg: String? = "Let's Encrypt",
    issuerCommonName: String? = "R11",
    subjectCommonName: String? = null,
    dnsNames: List<String> = emptyList(),
) = Certificate(
    der = der,
    notBefore = notBefore,
    notAfter = notBefore.plusSeconds(90 * 86_400L),
    issuerOrg = issuerOrg,
    issuerCommonName = issuerCommonName,
    subjectCommonName = subjectCommonName,
    dnsNames = dnsNames,
    ipAddresses = emptyList(),
)

fun makeMessage(
    id: String = "m1",
    from: String = "GitHub <noreply@github.com>",
    address: String = "noreply@github.com",
    auth: List<String> = emptyList(),
) = MessageDetail(
    id = id, from = from, fromAddress = address, to = "me@example.com", subject = "Hi",
    date = "Fri, 2 Oct 2026 09:00:00 +0000", body = "body", bodyType = BodyType.Text, authenticationResults = auth,
)

/** A mail service with canned answers that records what it is asked to do, for tests that must not touch the network. */
class FakeMail(
    private val known: Boolean = false,
    private val failsHistory: Boolean = false,
    private val failsActions: Boolean = false,
    private val message: MessageDetail = makeMessage(),
    private val inbox: List<MessageSummary> = emptyList(),
) : MailService {
    /** What was asked of the service, in order, e.g. `["archive m1", "move m1 work"]`. */
    val calls = CopyOnWriteArrayList<String>()
    val sent = CopyOnWriteArrayList<OutgoingMessage>()

    private fun record(call: String) {
        calls.add(call)
        if (failsActions) throw GmailException(500, "boom")
    }

    override suspend fun listInbox(maxResults: Int) = inbox
    override suspend fun getMessage(id: String) = message.copy(id = id)
    override suspend fun hasCorrespondence(address: String, excludingMessageId: String): Boolean {
        if (failsHistory) throw GmailException(500, "boom")
        return known
    }

    override suspend fun trash(id: String) = record("trash $id")
    override suspend fun archive(id: String) = record("archive $id")
    override suspend fun move(id: String, label: MailLabel) = record("move $id ${label.id}")
    override suspend fun moveTargets() = listOf(MailLabel("work", "Work"), MailLabel("SPAM", "Spam"))
    override suspend fun send(message: OutgoingMessage) {
        record("send")
        sent.add(message)
    }
}
