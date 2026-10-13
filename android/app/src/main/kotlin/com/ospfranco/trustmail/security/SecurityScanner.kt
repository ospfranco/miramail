package com.ospfranco.trustmail.security

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** [inFooter]: inside the email's footer (see `EmailHtml.FOOTER_SELECTOR`). */
data class FoundLink(val url: String, val text: String, val inFooter: Boolean = false)

data class ScannedLink(
    val url: String,
    val text: String,
    val host: String,
    val verdict: Verdict,
    val checks: List<LinkCheck>,
    val inFooter: Boolean = false,
)

data class SecurityCheck(val label: String, val detail: String, val verdict: Verdict)

/** [senderFailed]: a sender check failed (the email may be forged), so the header marks the sender's domain like an unsafe link. */
data class SecurityEvaluation(
    val verdict: Verdict,
    val summary: String,
    val checks: List<SecurityCheck>,
    val senderFailed: Boolean = false,
) {
    /** What the email header shows: the checks that found a problem, unsafe ones first. */
    val findings: List<SecurityCheck>
        get() = checks.filter { it.verdict == Verdict.Danger } + checks.filter { it.verdict == Verdict.Warning }
}

/** The finding about the email's links, only there when some look unsafe or suspicious. */
enum class LinkFindingLabel(val text: String, val verdict: Verdict, val description: String) {
    Unsafe(
        "Unsafe links",
        Verdict.Danger,
        "Some links in this email may lead to a fake or harmful website. Don't open the links marked with a red ✕.",
    ),
    Suspicious(
        "Suspicious links",
        Verdict.Warning,
        "Some links in this email look unusual. Tap one marked with a yellow ! to see why before anything opens.",
    ),
    ;

    val check: SecurityCheck get() = SecurityCheck(text, description, verdict)
}

data class ParsedUrl(val scheme: String, val host: String, val hasUserInfo: Boolean)

object SecurityScanner {
    private val URL_SHORTENERS = listOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly", "cutt.ly")

    private val urlPattern = Regex("""^([a-z][a-z0-9+.-]*):(?://([^/?#]*))?""", RegexOption.IGNORE_CASE)
    private val portPattern = Regex(""":\d+$""")
    private val textHostPattern =
        Regex("""^\s*(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,})(?:[/:?#]\S*)?\s*$""", RegexOption.IGNORE_CASE)
    private val ipPattern = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** Parses just the parts the checks need: scheme, host (without credentials or port) and whether credentials are present. */
    fun parseUrl(url: String): ParsedUrl? {
        val match = urlPattern.find(url.trim()) ?: return null
        val authority = match.groupValues[2]
        val at = authority.lastIndexOf('@')
        val host = (if (at == -1) authority else authority.substring(at + 1)).replace(portPattern, "").lowercase()
        return ParsedUrl(match.groupValues[1].lowercase(), host, at != -1)
    }

    /** Pulls a hostname out of link text that looks like a URL or bare domain, e.g. "paypal.com/login". */
    fun hostInText(text: String): String? = textHostPattern.find(text)?.groupValues?.get(1)?.lowercase()

    /** The URL without its query string or fragment, which mostly hold tracking parameters. */
    fun cleanUrl(url: String): String {
        val trimmed = url.trim()
        val end = trimmed.indexOfFirst { it == '?' || it == '#' }
        return if (end == -1) trimmed else trimmed.substring(0, end)
    }

    /**
     * The text of the warning shown before opening a link that isn't safe: what the link text shows
     * (when it looks like a link), where the link really goes, then why it's risky in plain words (unsafe problems
     * first, one sentence per kind) and what to do.
     */
    fun linkWarning(link: ScannedLink): String {
        val shown = if (hostInText(link.text) != null) "Shown as: ${cleanUrl(link.text)}" else null
        val target = "Goes to: ${cleanUrl(link.url)}"
        val problems = link.checks.filter { it.verdict == Verdict.Danger } + link.checks.filter { it.verdict == Verdict.Warning }
        val reasons = problems.map { it.kind.reason }.distinct()
        val advice = if (link.verdict == Verdict.Danger) {
            "Don't open it unless you're sure where it goes, and never enter a password or personal details there."
        } else {
            "Only open it if you trust where it goes."
        }
        return (listOf(listOfNotNull(shown, target).joinToString("\n")) + reasons + advice).joinToString("\n\n")
    }

    fun sameSite(a: String, b: String): Boolean {
        fun strip(host: String) = host.removePrefix("www.")
        val x = strip(a)
        val y = strip(b)
        return x == y || x.endsWith(".$y") || y.endsWith(".$x")
    }

    fun isIpAddress(host: String): Boolean = ipPattern.matches(host) || host.startsWith("[")

    /** Checks that only look at the URL and its visible text. */
    fun localChecks(link: FoundLink, scheme: String, host: String, hasUserInfo: Boolean): List<LinkCheck> {
        val checks = ArrayList<LinkCheck>()
        fun add(kind: LinkCheckKind, verdict: Verdict, detail: String) {
            checks.add(LinkCheck(kind, verdict, detail))
        }

        if (isIpAddress(host)) add(LinkCheckKind.LinkFormat, Verdict.Danger, "Points to a raw IP address instead of a domain.")
        if (hasUserInfo) add(LinkCheckKind.LinkFormat, Verdict.Danger, "Hides the real destination behind an \"@\".")
        val shownHost = hostInText(link.text)
        if (shownHost != null && !sameSite(shownHost, host)) {
            add(LinkCheckKind.DisplayText, Verdict.Danger, "Text shows $shownHost but the link goes to $host.")
        }
        if (scheme == "http") add(LinkCheckKind.Encryption, Verdict.Warning, "Not encrypted (http).")
        if (host.split(".").any { it.startsWith("xn--") }) {
            add(LinkCheckKind.LookalikeDomain, Verdict.Warning, "Uses look-alike (punycode) characters in the domain.")
        }
        if (host in URL_SHORTENERS) add(LinkCheckKind.Shortener, Verdict.Warning, "Shortened link hides the final destination.")
        return checks
    }

    private suspend fun scanLink(link: FoundLink): ScannedLink {
        fun result(host: String, checks: List<LinkCheck>) =
            ScannedLink(link.url, link.text, host, Verdict.worst(checks.map { it.verdict }), checks, link.inFooter)

        val parsed = parseUrl(link.url)
            ?: return result("", listOf(LinkCheck(LinkCheckKind.LinkFormat, Verdict.Warning, "Link could not be parsed.")))
        val (scheme, host, hasUserInfo) = parsed

        if (scheme == "mailto" || scheme == "tel") return result(host, emptyList())
        if (scheme != "http" && scheme != "https") {
            return result(host, listOf(LinkCheck(LinkCheckKind.LinkFormat, Verdict.Danger, "Uses a \"$scheme:\" link, which can run code.")))
        }

        val checks = localChecks(link, scheme, host, hasUserInfo).toMutableList()
        if (host.isNotEmpty() && !isIpAddress(host)) checks += LinkChecks.runNetworkChecks(host, scheme == "https")
        return result(host, checks)
    }

    /** Links deduplicated by URL, in first-seen order. A link counts as a footer link only if every copy of it is in the footer. */
    fun uniqueLinks(links: List<FoundLink>): List<FoundLink> {
        val unique = LinkedHashMap<String, FoundLink>()
        for (link in links) {
            val existing = unique[link.url]
            if (existing == null) {
                unique[link.url] = link
                continue
            }
            // Keep the entry with visible text, since that's what the mismatch check needs.
            val kept = if (existing.text.isEmpty() && link.text.isNotEmpty()) link else existing
            unique[link.url] = kept.copy(inFooter = existing.inFooter && link.inFooter)
        }
        return unique.values.toList()
    }

    suspend fun scanLinks(links: List<FoundLink>): List<ScannedLink> = coroutineScope {
        uniqueLinks(links).map { async { scanLink(it) } }.awaitAll()
    }

    /** With [ignoreFooterLinks], suspicious footer links don't count; unsafe ones always do. */
    fun evaluateEmail(senderChecks: List<SecurityCheck>, links: List<ScannedLink>, ignoreFooterLinks: Boolean = false): SecurityEvaluation {
        val bad = links.count { it.verdict == Verdict.Danger }
        val ignored = if (ignoreFooterLinks) links.count { it.inFooter && it.verdict == Verdict.Warning } else 0
        val suspicious = links.count { it.verdict == Verdict.Warning } - ignored
        // Safe links aren't listed: links are only mentioned when they are the problem.
        val checks = senderChecks + listOfNotNull(
            if (bad > 0) LinkFindingLabel.Unsafe.check else null,
            if (suspicious > 0) LinkFindingLabel.Suspicious.check else null,
        )
        val verdict = Verdict.worst(checks.map { it.verdict })
        val summary = when (verdict) {
            Verdict.Safe -> "No problems found"
            Verdict.Warning -> "Be careful with this email"
            Verdict.Danger -> "This email looks dangerous"
        }
        return SecurityEvaluation(
            verdict,
            summary,
            checks,
            senderFailed = senderChecks.any { it.verdict == Verdict.Danger },
        )
    }
}
