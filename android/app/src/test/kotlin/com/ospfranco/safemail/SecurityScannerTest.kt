package com.ospfranco.safemail

import com.ospfranco.safemail.security.FoundLink
import com.ospfranco.safemail.security.LinkCheck
import com.ospfranco.safemail.security.LinkCheckKind
import com.ospfranco.safemail.security.ParsedUrl
import com.ospfranco.safemail.security.ScannedLink
import com.ospfranco.safemail.security.SecurityCheck
import com.ospfranco.safemail.security.SecurityScanner
import com.ospfranco.safemail.security.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SecurityScannerTest {
    @get:Rule val psl = PublicSuffixRule()

    @Test
    fun parsesUrls() {
        assertEquals(ParsedUrl("https", "example.com", false), SecurityScanner.parseUrl("https://Example.COM/path?q=1"))
        assertEquals(ParsedUrl("http", "example.com", false), SecurityScanner.parseUrl("  http://example.com:8080/x "))
        assertEquals(ParsedUrl("https", "evil.com", true), SecurityScanner.parseUrl("https://paypal.com@evil.com/"))
        assertEquals(ParsedUrl("https", "evil.com", true), SecurityScanner.parseUrl("https://user:pw@evil.com:99/"))
        assertEquals(ParsedUrl("mailto", "", false), SecurityScanner.parseUrl("mailto:a@b.com"))
        assertEquals(ParsedUrl("http", "[::1]", false), SecurityScanner.parseUrl("http://[::1]:80/"))
        assertNull(SecurityScanner.parseUrl("/relative/path"))
        assertNull(SecurityScanner.parseUrl("not a url"))
    }

    @Test
    fun findsHostsInLinkText() {
        assertEquals("paypal.com", SecurityScanner.hostInText("paypal.com/login"))
        assertEquals("paypal.com", SecurityScanner.hostInText(" https://www.Paypal.com "))
        assertNull(SecurityScanner.hostInText("Click here"))
        assertNull(SecurityScanner.hostInText("Visit paypal.com today"))
        assertNull(SecurityScanner.hostInText(""))
    }

    @Test
    fun comparesSites() {
        assertTrue(SecurityScanner.sameSite("www.github.com", "github.com"))
        assertTrue(SecurityScanner.sameSite("mail.github.com", "github.com"))
        assertFalse(SecurityScanner.sameSite("github.com", "github.com.evil.net"))
        assertFalse(SecurityScanner.sameSite("paypal.com", "paypa1.com"))
    }

    @Test
    fun detectsIpAddresses() {
        assertTrue(SecurityScanner.isIpAddress("192.168.0.1"))
        assertTrue(SecurityScanner.isIpAddress("[::1]"))
        assertFalse(SecurityScanner.isIpAddress("1.2.3.example.com"))
        assertFalse(SecurityScanner.isIpAddress("example.com"))
    }

    private fun local(url: String, text: String = ""): List<LinkCheck> {
        val parsed = SecurityScanner.parseUrl(url)!!
        return SecurityScanner.localChecks(FoundLink(url, text), parsed.scheme, parsed.host, parsed.hasUserInfo)
    }

    @Test
    fun localChecksFlagTrickyLinks() {
        assertTrue(local("https://example.com/x", "Read more").isEmpty())
        assertTrue(local("https://example.com/x", "example.com").isEmpty())

        assertEquals(listOf(LinkCheck(LinkCheckKind.LinkFormat, Verdict.Danger, "Points to a raw IP address instead of a domain.")), local("https://10.0.0.1/login"))
        assertEquals(listOf(LinkCheck(LinkCheckKind.LinkFormat, Verdict.Danger, "Hides the real destination behind an \"@\".")), local("https://paypal.com@evil.com/"))
        assertEquals(
            listOf(LinkCheck(LinkCheckKind.DisplayText, Verdict.Danger, "Text shows paypal.com but the link goes to evil.com.")),
            local("https://evil.com/", "paypal.com/login"),
        )
        assertEquals(listOf(LinkCheck(LinkCheckKind.Encryption, Verdict.Warning, "Not encrypted (http).")), local("http://example.com"))
        assertEquals(
            listOf(LinkCheck(LinkCheckKind.LookalikeDomain, Verdict.Warning, "Uses look-alike (punycode) characters in the domain.")),
            local("https://xn--pypal-4ve.com"),
        )
        assertEquals(listOf(LinkCheck(LinkCheckKind.Shortener, Verdict.Warning, "Shortened link hides the final destination.")), local("https://bit.ly/abc"))
    }

    @Test
    fun dedupesLinksKeepingTheOneWithText() {
        val unique = SecurityScanner.uniqueLinks(
            listOf(
                FoundLink("https://a.com", ""),
                FoundLink("https://b.com", "B"),
                FoundLink("https://a.com", "A"),
                FoundLink("https://b.com", "other"),
            ),
        )
        assertEquals(listOf(FoundLink("https://a.com", "A"), FoundLink("https://b.com", "B")), unique)
    }

    @Test
    fun scansLinksThatNeedNoNetwork() = runTest {
        val scanned = SecurityScanner.scanLinks(
            listOf(
                FoundLink("mailto:a@b.com", "mail"),
                FoundLink("tel:+123", "call"),
                FoundLink("javascript:alert(1)", "x"),
                FoundLink("http://10.0.0.1/a", "ip"),
                FoundLink("/relative", "rel"),
            ),
        )
        assertEquals(listOf("mailto:a@b.com", "tel:+123", "javascript:alert(1)", "http://10.0.0.1/a", "/relative"), scanned.map { it.url })
        assertEquals(listOf(Verdict.Safe, Verdict.Safe, Verdict.Danger, Verdict.Danger, Verdict.Warning), scanned.map { it.verdict })
        assertEquals("Uses a \"javascript:\" link, which can run code.", scanned[2].checks.first().detail)
        assertEquals("10.0.0.1", scanned[3].host)
        assertEquals("Link could not be parsed.", scanned[4].checks.first().detail)
    }

    private fun scanned(verdict: Verdict) = ScannedLink("https://x.com/${verdict.id}", "", "x.com", verdict, emptyList())

    @Test
    fun evaluatesEmail() {
        val safeSender = listOf(SecurityCheck("Sender verified", "", Verdict.Safe))

        val none = SecurityScanner.evaluateEmail(safeSender, emptyList())
        assertEquals(Verdict.Safe, none.verdict)
        assertEquals("No problems found", none.summary)
        assertEquals(SecurityCheck("No links", "No problems found in links.", Verdict.Safe), none.checks.last())

        assertEquals("1 link scanned", SecurityScanner.evaluateEmail(safeSender, listOf(scanned(Verdict.Safe))).checks.last().label)

        val mixed = SecurityScanner.evaluateEmail(
            safeSender,
            listOf(scanned(Verdict.Safe), scanned(Verdict.Warning), scanned(Verdict.Danger), scanned(Verdict.Danger)),
        )
        assertEquals(Verdict.Danger, mixed.verdict)
        assertEquals("This email looks dangerous", mixed.summary)
        assertEquals(SecurityCheck("4 links scanned", "2 unsafe, 1 suspicious", Verdict.Danger), mixed.checks.last())

        val warnSender = listOf(SecurityCheck("First message from this sender", "", Verdict.Warning))
        val warn = SecurityScanner.evaluateEmail(warnSender, listOf(scanned(Verdict.Safe)))
        assertEquals(Verdict.Warning, warn.verdict)
        assertEquals("Be careful with this email", warn.summary)
    }
}
