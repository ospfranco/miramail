package com.ospfranco.trustmail.security

import androidx.annotation.StringRes
import com.ospfranco.trustmail.R
import com.ospfranco.trustmail.mail.KnownSenders
import com.ospfranco.trustmail.mail.MailService
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.mail.bareAddress

/** Result values from RFC 8601 Authentication-Results. */
private enum class AuthResult(val value: String) { Pass("pass"), Fail("fail") }

private enum class AuthMethod(val value: String) { Dmarc("dmarc"), Dkim("dkim"), Spf("spf") }

/**
 * The sender checks, so the evaluation can tell which one raised a problem. Each has a fixed verdict and a plain
 * description for the email header, written for someone who doesn't know how email works (string resources).
 */
enum class SenderCheckLabel(@StringRes val title: Int, val verdict: Verdict, @StringRes val description: Int) {
    Verified(R.string.sender_verified, Verdict.Safe, R.string.sender_verified_description),
    NotVerified(R.string.sender_not_verified, Verdict.Warning, R.string.sender_not_verified_description),
    Failed(R.string.sender_failed, Verdict.Danger, R.string.sender_failed_description),
    Known(R.string.sender_known, Verdict.Safe, R.string.sender_known_description),
    FirstMessage(R.string.sender_first_message, Verdict.Warning, R.string.sender_first_message_description),
    HistoryUnknown(R.string.sender_history_unknown, Verdict.Warning, R.string.sender_history_unknown_description),
    ;

    val check: SecurityCheck get() = SecurityCheck(title, description, verdict)
}

data class MethodResult(val method: String, val result: String, val properties: Map<String, String>)

object SenderChecks {
    private val comment = Regex("""\([^()]*\)""")
    private val whitespace = Regex("""\s+""")
    private val statementHead = Regex("""^([a-z0-9-]+)=([a-z]+)$""", RegexOption.IGNORE_CASE)

    /**
     * Parses Gmail's Authentication-Results header, e.g.
     * `mx.google.com; dkim=pass header.i=@github.com; spf=pass (...) smtp.mailfrom=x@github.com; dmarc=pass header.from=github.com`
     */
    fun parseAuthenticationResults(header: String): List<MethodResult> {
        var text = header
        // Drop (comments), innermost first so nested parentheses are handled.
        while (comment.containsMatchIn(text)) text = text.replace(comment, " ")
        val statements = text.split(';').drop(1) // first segment is the authserv-id

        return statements.mapNotNull { statement ->
            val words = statement.trim().split(whitespace)
            val match = statementHead.matchEntire(words.first()) ?: return@mapNotNull null
            val properties = HashMap<String, String>()
            for (word in words.drop(1)) {
                val eq = word.indexOf('=')
                if (eq > 0) properties[word.substring(0, eq).lowercase()] = word.substring(eq + 1).lowercase()
            }
            MethodResult(match.groupValues[1].lowercase(), match.groupValues[2].lowercase(), properties)
        }
    }

    private fun domainOf(addressOrDomain: String?): String? {
        if (addressOrDomain == null) return null
        return addressOrDomain.substring(addressOrDomain.lastIndexOf('@') + 1).removeSuffix(".").ifEmpty { null }
    }

    private fun organizationalDomain(host: String) = PublicSuffixList.shared.registrableDomain(host) ?: host

    /** DMARC "relaxed" alignment: same organizational domain (e.g. mail.github.com aligns with github.com). */
    private fun aligned(a: String?, b: String) = a != null && organizationalDomain(a) == organizationalDomain(b)

    fun checkSenderDomain(message: MessageDetail): SecurityCheck = checkSenderDomain(message.fromAddress, message.authenticationResults)

    fun checkSenderDomain(fromAddress: String, authenticationResults: List<String>): SecurityCheck {
        val fromDomain = domainOf(fromAddress) ?: "unknown"
        val results = authenticationResults.flatMap(::parseAuthenticationResults)
        if (results.isEmpty()) return SenderCheckLabel.NotVerified.check

        val dmarc = results.firstOrNull { it.method == AuthMethod.Dmarc.value }
        if (dmarc?.result == AuthResult.Pass.value) return SenderCheckLabel.Verified.check
        if (dmarc?.result == AuthResult.Fail.value) return SenderCheckLabel.Failed.check

        // No conclusive DMARC (the domain has no policy, or a lookup error): judge SPF/DKIM directly.
        val alignedDkim = results.any {
            it.method == AuthMethod.Dkim.value && it.result == AuthResult.Pass.value &&
                aligned(domainOf(it.properties["header.d"] ?: it.properties["header.i"]), fromDomain)
        }
        val alignedSpf = results.any {
            it.method == AuthMethod.Spf.value && it.result == AuthResult.Pass.value && aligned(domainOf(it.properties["smtp.mailfrom"]), fromDomain)
        }
        if (alignedDkim || alignedSpf) return SenderCheckLabel.Verified.check

        val failed = results.any {
            (it.method == AuthMethod.Dkim.value || it.method == AuthMethod.Spf.value) && it.result == AuthResult.Fail.value
        }
        return if (failed) SenderCheckLabel.Failed.check else SenderCheckLabel.NotVerified.check
    }

    /**
     * Whether you've had other mail from or to [address]: a sender already seen in the inbox counts even after
     * their emails were deleted, otherwise Gmail's search decides.
     */
    suspend fun isKnownSender(address: String, excludingMessageId: String, mail: MailService, senders: KnownSenders): Boolean =
        senders.knows(address, besides = excludingMessageId) || mail.hasCorrespondence(address, excludingMessageId)

    suspend fun checkSenderHistory(message: MessageDetail, mail: MailService, senders: KnownSenders = KnownSenders()): SecurityCheck = try {
        if (isKnownSender(message.fromAddress, message.id, mail, senders)) SenderCheckLabel.Known.check else SenderCheckLabel.FirstMessage.check
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        SenderCheckLabel.HistoryUnknown.check
    }

    suspend fun checkSender(message: MessageDetail, mail: MailService, senders: KnownSenders = KnownSenders()): List<SecurityCheck> =
        listOf(checkSenderDomain(message), checkSenderHistory(message, mail, senders))
}

/** The sender failed Gmail's checks (the email may be forged), so the inbox marks it like an unsafe link. */
val MessageSummary.senderFailed: Boolean
    get() = SenderChecks.checkSenderDomain(bareAddress(from), authenticationResults).verdict == Verdict.Danger
