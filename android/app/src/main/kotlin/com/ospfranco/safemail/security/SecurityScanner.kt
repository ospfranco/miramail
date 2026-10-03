package com.ospfranco.safemail.security

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class FoundLink(val url: String, val text: String)

data class ScannedLink(
    val url: String,
    val text: String,
    val host: String,
    val verdict: Verdict,
    val checks: List<LinkCheck>,
)

data class SecurityCheck(val label: String, val detail: String, val verdict: Verdict)

data class SecurityEvaluation(val verdict: Verdict, val summary: String, val checks: List<SecurityCheck>)

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
            ScannedLink(link.url, link.text, host, Verdict.worst(checks.map { it.verdict }), checks)

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

    /** Links deduplicated by URL, in first-seen order. */
    fun uniqueLinks(links: List<FoundLink>): List<FoundLink> {
        val unique = LinkedHashMap<String, FoundLink>()
        for (link in links) {
            val existing = unique[link.url]
            // Keep the entry with visible text, since that's what the mismatch check needs.
            if (existing == null || (existing.text.isEmpty() && link.text.isNotEmpty())) unique[link.url] = link
        }
        return unique.values.toList()
    }

    suspend fun scanLinks(links: List<FoundLink>): List<ScannedLink> = coroutineScope {
        uniqueLinks(links).map { async { scanLink(it) } }.awaitAll()
    }

    fun evaluateEmail(senderChecks: List<SecurityCheck>, links: List<ScannedLink>): SecurityEvaluation {
        val bad = links.count { it.verdict == Verdict.Danger }
        val suspicious = links.count { it.verdict == Verdict.Warning }
        val linkCheck = SecurityCheck(
            label = if (links.isEmpty()) "No links" else "${links.size} link${if (links.size == 1) "" else "s"} scanned",
            detail = if (bad > 0 || suspicious > 0) {
                listOfNotNull(
                    if (bad > 0) "$bad unsafe" else null,
                    if (suspicious > 0) "$suspicious suspicious" else null,
                ).joinToString(", ")
            } else {
                "No problems found in links."
            },
            verdict = if (bad > 0) Verdict.Danger else if (suspicious > 0) Verdict.Warning else Verdict.Safe,
        )

        val checks = senderChecks + linkCheck
        val verdict = Verdict.worst(checks.map { it.verdict })
        val summary = when (verdict) {
            Verdict.Safe -> "No problems found"
            Verdict.Warning -> "Be careful with this email"
            Verdict.Danger -> "This email looks dangerous"
        }
        return SecurityEvaluation(verdict, summary, checks)
    }
}
