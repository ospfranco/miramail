package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.BodyType
import com.ospfranco.trustmail.mail.GmailException
import com.ospfranco.trustmail.mail.MailLabel
import com.ospfranco.trustmail.mail.MailService
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.mail.OutgoingMessage
import com.ospfranco.trustmail.security.Certificate
import com.ospfranco.trustmail.security.KnownDomains
import com.ospfranco.trustmail.security.PublicSuffixList
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.w3c.dom.Element

object Fixture {
    fun text(name: String): String =
        checkNotNull(Fixture::class.java.classLoader.getResource(name)) { "missing fixture $name" }.readText().trim()
}

/** Loads the same Public Suffix List and known-domains list the app ships, since the app normally does it in `TrustmailApplication`. */
class PublicSuffixRule : TestWatcher() {
    override fun starting(description: Description) {
        PublicSuffixList.shared = PublicSuffixList(File("src/main/assets/public_suffix_list.dat").readText())
        KnownDomains.shared = KnownDomains(File("src/main/assets/known_domains.txt").readText())
    }
}

/**
 * The app's strings read straight from strings.xml (English, or German in values-de), since JVM tests have no Android resources.
 * Resource ids are matched to names through the generated R class.
 */
class TestStrings private constructor(folder: String) : Strings {
    val byName: Map<String, String> = run {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml"))
        val elements = document.getElementsByTagName("string")
        (0 until elements.length).associate { index ->
            val element = elements.item(index) as Element
            element.getAttribute("name") to unescape(element.textContent)
        }
    }

    override fun get(id: Int, vararg args: Any): String {
        val name = checkNotNull(names[id]) { "no string resource with id $id" }
        val text = checkNotNull(byName[name]) { "no string $name" }
        return if (args.isEmpty()) text else String.format(Locale.ROOT, text, *args)
    }

    companion object {
        val english = TestStrings("values")
        val german = TestStrings("values-de")

        private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

        /** Undoes the escapes aapt reads (\', \", \n, \\, and a leading \@ or \?). */
        private fun unescape(text: String): String = Regex("""\\(.)""").replace(text) { if (it.groupValues[1] == "n") "\n" else it.groupValues[1] }
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
