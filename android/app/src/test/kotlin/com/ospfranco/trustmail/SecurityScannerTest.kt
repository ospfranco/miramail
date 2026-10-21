package com.ospfranco.trustmail

import com.ospfranco.trustmail.security.FoundLink
import com.ospfranco.trustmail.security.LinkCheck
import com.ospfranco.trustmail.security.LinkCheckKind
import com.ospfranco.trustmail.security.LinkFindingLabel
import com.ospfranco.trustmail.security.ParsedUrl
import com.ospfranco.trustmail.security.ScannedLink
import com.ospfranco.trustmail.security.SecurityScanner
import com.ospfranco.trustmail.security.SenderCheckLabel
import com.ospfranco.trustmail.security.Verdict
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
    fun cleansUrls() {
        assertEquals("https://a.com/path", SecurityScanner.cleanUrl(" https://a.com/path?utm_source=x&id=1#top "))
        assertEquals("https://a.com/", SecurityScanner.cleanUrl("https://a.com/#/route?x=1"))
        assertEquals("paypal.com/login", SecurityScanner.cleanUrl("paypal.com/login"))
    }

    @Test
    fun linkWarningShowsTheShownAndRealUrls() {
        val checks = listOf(
            LinkCheck(LinkCheckKind.DisplayText, Verdict.Danger, "Text shows paypal.com but the link goes to login.example.net."),
            LinkCheck(LinkCheckKind.DomainAge, Verdict.Safe, "Registered long ago."),
            LinkCheck(LinkCheckKind.Encryption, Verdict.Warning, "Not encrypted (http)."),
        )
        val mismatch = ScannedLink(
            "http://login.example.net/verify?session=abc#x", "paypal.com/login?ref=mail", "login.example.net", Verdict.Danger, checks,
        )
        // Why it's risky in plain words, unsafe problems first and one sentence per kind, then what to do.
        assertEquals(
            """
            Shown as: paypal.com/login
            Goes to: http://login.example.net/verify

            The link's text shows one website, but it takes you to another. Fake emails do this to look trustworthy.

            The connection to this website isn't secure, so anything you type there, like a password, could be seen by others.

            Don't open it unless you're sure where it goes, and never enter a password or personal details there.
            """.trimIndent(),
            SecurityScanner.linkWarning(mismatch, TestStrings.english),
        )

        val shortener = ScannedLink(
            "https://bit.ly/abc?x=1", "Fix my account", "bit.ly", Verdict.Warning,
            listOf(LinkCheck(LinkCheckKind.Shortener, Verdict.Warning, "Shortened link hides the final destination.")),
        )
        assertEquals(
            "Goes to: https://bit.ly/abc\n\nThe link is shortened, so you can't see where it really goes before you open it.\n\nOnly open it if you trust where it goes.",
            SecurityScanner.linkWarning(shortener, TestStrings.english),
        )
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
    fun aLinkIsInTheFooterOnlyIfEveryCopyIs() {
        val unique = SecurityScanner.uniqueLinks(
            listOf(
                FoundLink("https://a.com", "", inFooter = true),
                FoundLink("https://b.com", "B", inFooter = true),
                FoundLink("https://a.com", "A", inFooter = false),
                FoundLink("https://b.com", "", inFooter = true),
            ),
        )
        assertEquals(listOf(FoundLink("https://a.com", "A", inFooter = false), FoundLink("https://b.com", "B", inFooter = true)), unique)
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
        val safeSender = listOf(SenderCheckLabel.Verified.check)

        // Links are only mentioned when some are a problem.
        val none = SecurityScanner.evaluateEmail(safeSender, emptyList())
        assertEquals(Verdict.Safe, none.verdict)
        assertEquals(R.string.summary_safe, none.summary)
        assertEquals(safeSender, none.checks)
        assertEquals(safeSender, SecurityScanner.evaluateEmail(safeSender, listOf(scanned(Verdict.Safe))).checks)

        val mixed = SecurityScanner.evaluateEmail(
            safeSender,
            listOf(scanned(Verdict.Safe), scanned(Verdict.Warning), scanned(Verdict.Danger), scanned(Verdict.Danger)),
        )
        assertEquals(Verdict.Danger, mixed.verdict)
        assertEquals(R.string.summary_danger, mixed.summary)
        assertEquals(safeSender + LinkFindingLabel.Unsafe.check + LinkFindingLabel.Suspicious.check, mixed.checks)

        val warnSender = listOf(SenderCheckLabel.FirstMessage.check)
        val warn = SecurityScanner.evaluateEmail(warnSender, listOf(scanned(Verdict.Safe)))
        assertEquals(Verdict.Warning, warn.verdict)
        assertEquals(R.string.summary_warning, warn.summary)
        assertFalse(warn.senderFailed)
        assertFalse("unsafe links alone don't mark the sender", mixed.senderFailed)
    }

    @Test
    fun theHeaderShowsOnlyProblemsUnsafeFirst() {
        val evaluation = SecurityScanner.evaluateEmail(
            listOf(SenderCheckLabel.Verified.check, SenderCheckLabel.FirstMessage.check),
            listOf(scanned(Verdict.Danger)),
        )
        assertEquals(listOf(LinkFindingLabel.Unsafe.check, SenderCheckLabel.FirstMessage.check), evaluation.findings)
        assertTrue(
            SecurityScanner.evaluateEmail(listOf(SenderCheckLabel.Verified.check, SenderCheckLabel.Known.check), listOf(scanned(Verdict.Safe))).findings.isEmpty(),
        )
    }

    @Test
    fun marksTheSenderOnlyWhenASenderCheckFails() {
        val failed = listOf(
            SenderCheckLabel.Failed.check,
            SenderCheckLabel.Known.check,
        )
        assertTrue(SecurityScanner.evaluateEmail(failed, emptyList()).senderFailed)
        val unverified = listOf(SenderCheckLabel.NotVerified.check)
        assertFalse(SecurityScanner.evaluateEmail(unverified, emptyList()).senderFailed)
    }

    private fun footer(verdict: Verdict) = ScannedLink("https://x.com/footer/${verdict.id}", "", "x.com", verdict, emptyList(), inFooter = true)

    @Test
    fun ignoresSuspiciousFooterLinksOnlyWhenAsked() {
        val safeSender = listOf(SenderCheckLabel.Verified.check)
        val links = listOf(scanned(Verdict.Safe), footer(Verdict.Warning))

        val counted = SecurityScanner.evaluateEmail(safeSender, links)
        assertEquals(Verdict.Warning, counted.verdict)
        assertEquals(safeSender + LinkFindingLabel.Suspicious.check, counted.checks)

        val ignored = SecurityScanner.evaluateEmail(safeSender, links, ignoreFooterLinks = true)
        assertEquals(Verdict.Safe, ignored.verdict)
        assertEquals(R.string.summary_safe, ignored.summary)
        assertEquals(safeSender, ignored.checks)

        // An unsafe footer link always counts.
        val unsafe = SecurityScanner.evaluateEmail(
            safeSender,
            listOf(footer(Verdict.Warning), footer(Verdict.Danger), scanned(Verdict.Warning)),
            ignoreFooterLinks = true,
        )
        assertEquals(Verdict.Danger, unsafe.verdict)
        assertEquals(safeSender + LinkFindingLabel.Unsafe.check + LinkFindingLabel.Suspicious.check, unsafe.checks)
    }

    /** Every card in the security panel says, in plain words, what was found and what to do. */
    @Test
    fun describesEveryCheckInPlainWords() {
        val strings = TestStrings.english
        assertEquals(
            listOf(
                "Sender verified: Gmail confirmed that this email really comes from the address it shows.",
                "Sender not verified: Gmail couldn't confirm that this email really comes from the address it shows. That's often harmless, but someone could be pretending to be this sender. Be careful if it asks you to do something or to share information.",
                "Sender verification failed: This email pretends to come from an address that didn't send it, so it's probably a fake. Don't open its links or reply with personal details. If it claims to be from someone you know, contact them another way.",
                "Known sender: You've exchanged emails with this address before.",
                "First message from this sender: You haven't exchanged email with this address before. If you did not expect this email, pay very close attention to it.",
                "Sender history unknown: Couldn't search your past email in order to verify if you have exchanged email with this address before.",
            ),
            SenderCheckLabel.entries.map { "${strings.get(it.title)}: ${strings.get(it.description)}" },
        )
        assertEquals(
            listOf(
                "Unsafe links: Some links in this email may lead to a fake or harmful website. Don't open the links marked with a red ✕.",
                "Suspicious links: Some links in this email look unusual. Tap one marked with a yellow ! to see why before anything opens.",
            ),
            LinkFindingLabel.entries.map { "${strings.get(it.title)}: ${strings.get(it.description)}" },
        )
        assertEquals(
            listOf(Verdict.Safe, Verdict.Warning, Verdict.Danger, Verdict.Safe, Verdict.Warning, Verdict.Warning),
            SenderCheckLabel.entries.map { it.verdict },
        )
        assertEquals(listOf(Verdict.Danger, Verdict.Warning), LinkFindingLabel.entries.map { it.verdict })
    }
}
