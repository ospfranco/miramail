package com.ospfranco.trustmail.security

import com.ospfranco.trustmail.mail.MailService
import com.ospfranco.trustmail.mail.MessageDetail
import com.ospfranco.trustmail.mail.MessageSummary
import com.ospfranco.trustmail.mail.bareAddress

/** Result values from RFC 8601 Authentication-Results. */
private enum class AuthResult(val value: String) { Pass("pass"), Fail("fail") }

private enum class AuthMethod(val value: String) { Dmarc("dmarc"), Dkim("dkim"), Spf("spf") }

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

        if (results.isEmpty()) {
            return SecurityCheck(
                "Sender not verified",
                "Gmail recorded no authentication results, so $fromDomain can't be confirmed as the real sender.",
                Verdict.Warning,
            )
        }

        val dmarc = results.firstOrNull { it.method == AuthMethod.Dmarc.value }
        if (dmarc?.result == AuthResult.Pass.value) {
            return SecurityCheck("Sender verified", "Gmail confirmed this email really comes from $fromDomain (DMARC).", Verdict.Safe)
        }
        if (dmarc?.result == AuthResult.Fail.value) {
            return SecurityCheck(
                "Sender verification failed",
                "$fromDomain says it didn't send this email (DMARC failed). It may be forged.",
                Verdict.Danger,
            )
        }

        // No conclusive DMARC (the domain has no policy, or a lookup error): judge SPF/DKIM directly.
        val dkimPasses = results.filter { it.method == AuthMethod.Dkim.value && it.result == AuthResult.Pass.value }
        val spfPasses = results.filter { it.method == AuthMethod.Spf.value && it.result == AuthResult.Pass.value }
        val alignedDkim = dkimPasses.firstOrNull { aligned(domainOf(it.properties["header.d"] ?: it.properties["header.i"]), fromDomain) }
        val alignedSpf = spfPasses.firstOrNull { aligned(domainOf(it.properties["smtp.mailfrom"]), fromDomain) }

        if (alignedDkim != null || alignedSpf != null) {
            return SecurityCheck(
                "Sender verified",
                "Gmail confirmed this email was ${if (alignedDkim != null) "signed" else "sent"} by $fromDomain (${if (alignedDkim != null) "DKIM" else "SPF"}).",
                Verdict.Safe,
            )
        }

        val failed = results.any {
            (it.method == AuthMethod.Dkim.value || it.method == AuthMethod.Spf.value) && it.result == AuthResult.Fail.value
        }
        if (failed) {
            return SecurityCheck("Sender verification failed", "This email failed $fromDomain's sender checks. It may be forged.", Verdict.Danger)
        }

        val relay = domainOf(
            dkimPasses.firstOrNull()?.properties?.get("header.d")
                ?: dkimPasses.firstOrNull()?.properties?.get("header.i")
                ?: spfPasses.firstOrNull()?.properties?.get("smtp.mailfrom"),
        )
        return SecurityCheck(
            "Sender not verified",
            if (relay != null) {
                "Sent through $relay on behalf of $fromDomain, but $fromDomain itself isn't verified."
            } else {
                "$fromDomain couldn't be verified as the real sender."
            },
            Verdict.Warning,
        )
    }

    suspend fun checkSenderHistory(message: MessageDetail, mail: MailService): SecurityCheck = try {
        val known = mail.hasCorrespondence(message.fromAddress, message.id)
        if (known) {
            SecurityCheck("Known sender", "You've exchanged email with this address before.", Verdict.Safe)
        } else {
            SecurityCheck("First message from this sender", "You haven't exchanged email with this address before.", Verdict.Warning)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        SecurityCheck("Sender history unknown", "Couldn't search your past email.", Verdict.Warning)
    }

    suspend fun checkSender(message: MessageDetail, mail: MailService): List<SecurityCheck> =
        listOf(checkSenderDomain(message), checkSenderHistory(message, mail))
}

/** The sender failed Gmail's checks (the email may be forged), so the inbox marks it like an unsafe link. */
val MessageSummary.senderFailed: Boolean
    get() = SenderChecks.checkSenderDomain(bareAddress(from), authenticationResults).verdict == Verdict.Danger
