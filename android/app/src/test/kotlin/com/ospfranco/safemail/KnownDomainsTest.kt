package com.ospfranco.safemail

import com.ospfranco.safemail.security.CertificateHistory
import com.ospfranco.safemail.security.KnownDomains
import com.ospfranco.safemail.security.LinkCheck
import com.ospfranco.safemail.security.LinkCheckKind
import com.ospfranco.safemail.security.LinkChecks
import com.ospfranco.safemail.security.PublicSuffixList
import com.ospfranco.safemail.security.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class KnownDomainsTest {
    @get:Rule val psl = PublicSuffixRule()

    private val known get() = KnownDomains.shared
    private val suffixes get() = PublicSuffixList.shared

    @Test
    fun parsesTheFileFormat() {
        val list = KnownDomains("# a comment\n\nGoogle.com\n  allianz.de  \r\nadmin.ch\ngoogle.com\n#spiegel.de\n")
        assertEquals(setOf("google.com", "allianz.de", "admin.ch"), list.domains)
        assertTrue(list.contains("GOOGLE.com"))
        assertFalse(list.contains("spiegel.de"))
        assertTrue(KnownDomains("").domains.isEmpty())
    }

    @Test
    fun recognizesKnownDomainsAndEverythingUnderThem() {
        for (host in listOf(
            "google.com", "www.google.com", "mail.google.com", "WWW.ALLIANZ.DE", "login.sparkasse.de", "www.youtube.com",
            "m.facebook.com", "www.amazon.co.uk", "www.bbc.co.uk", "www.orf.at", "www.bmf.gv.at", "finanzonline.bmf.gv.at",
            "oesterreich.gv.at", "www.admin.ch", "bag.admin.ch", "tickets.sbb.ch", "www.deutsche-bank.de", "google.com.",
        )) {
            assertTrue(host, known.includes(host, suffixes))
        }
    }

    @Test
    fun lookalikesAndImpostersAreNeverKnown() {
        for (host in listOf(
            "g00gle.com", "google.com.evil.net", "evil-google.com", "login-google.com", "googIe.com", "allianz-versicherung.de",
            "allianz.de.evil.com", "sparkasse-login.de", "sparkasse.de.kunden-portal.net", "paypa1.com", "deutsche-bank.de-login.com",
            "example.com", "co.uk", "gv.at", "localhost", "", ".",
        )) {
            assertFalse(host, known.includes(host, suffixes))
        }
    }

    @Test
    fun platformsWhereAnyoneCanPublishAreNeverKnown() {
        for (platform in listOf(
            "blogspot.com", "github.io", "gitlab.io", "herokuapp.com", "netlify.app", "vercel.app", "pages.dev", "workers.dev",
            "web.app", "firebaseapp.com", "appspot.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com",
            "googleusercontent.com", "googleapis.com", "sharepoint.com", "atlassian.net", "wordpress.com", "wixsite.com",
            "weebly.com", "myshopify.com", "medium.com", "tumblr.com", "bit.ly", "tinyurl.com", "t.co", "goo.gl",
        )) {
            assertFalse("$platform must not be listed", platform in known.domains)
            assertFalse(platform, known.includes("login-paypal.$platform", suffixes))
        }
    }

    @Test
    fun theShippedListIsLargeAndEveryEntryIsARegistrableDomain() {
        assertTrue(known.domains.size > 800)
        for (entry in known.domains) {
            assertTrue(entry, entry == entry.lowercase() && !entry.startsWith(".") && !entry.endsWith("."))
            assertEquals("$entry is not its own registrable domain", entry, suffixes.registrableDomain(entry))
        }
    }

    @Test
    fun coversTheBigNamesAndGermanSpeakingCountriesInDepth() {
        val global = listOf("google.com", "youtube.com", "facebook.com", "microsoft.com", "apple.com", "amazon.com", "paypal.com", "linkedin.com", "netflix.com", "instagram.com", "whatsapp.com")
        val germany = listOf(
            "allianz.de", "sparkasse.de", "deutsche-bank.de", "commerzbank.de", "ing.de", "dkb.de", "postbank.de", "telekom.de",
            "t-online.de", "web.de", "gmx.de", "spiegel.de", "zeit.de", "bild.de", "tagesschau.de", "bahn.de", "lufthansa.com",
            "dhl.de", "otto.de", "zalando.de", "mediamarkt.de", "bmw.com", "siemens.com", "sap.com", "bund.de", "elster.de",
            "arbeitsagentur.de", "barmer.de", "tk.de", "check24.de",
        )
        val austria = listOf("orf.at", "derstandard.at", "willhaben.at", "erstebank.at", "raiffeisen.at", "a1.net", "oebb.at", "post.at", "oesterreich.gv.at", "wien.gv.at", "uniqa.at")
        val switzerland = listOf("admin.ch", "sbb.ch", "post.ch", "ubs.com", "postfinance.ch", "swisscom.ch", "nzz.ch", "srf.ch", "migros.ch", "coop.ch", "galaxus.ch", "zh.ch")
        for (domain in global + germany + austria + switzerland) assertTrue("$domain is missing", known.contains(domain))
    }
}

class DomainAgeLookupTest {
    private val known = KnownDomains("google.com\nallianz.de\n")
    private val now = date("2026-10-02T12:00:00Z")

    @Test
    fun aWellKnownDomainSaysSoAndIsNeverLookedUp() = runTest {
        val calls = AtomicInteger()
        val result = LinkChecks.domainAgeResult("google.com", encrypted = true, known = known, now = now) {
            calls.incrementAndGet()
            null
        }
        assertEquals(LinkCheck(LinkCheckKind.DomainAge, Verdict.Safe, "google.com is a well-known domain, so its age isn't checked."), result)
        assertEquals(0, calls.get())
    }

    @Test
    fun aWellKnownDomainIsSkippedForUnencryptedLinksToo() = runTest {
        val calls = AtomicInteger()
        val result = LinkChecks.domainAgeResult("allianz.de", encrypted = false, known = known, now = now) {
            calls.incrementAndGet()
            null
        }
        assertEquals(Verdict.Safe, result.verdict)
        assertEquals(0, calls.get())
    }

    @Test
    fun anyOtherDomainIsLookedUpOnceAndJudgedAsBefore() = runTest {
        val calls = AtomicInteger()
        val young = CertificateHistory(now.minusSeconds(3 * 86_400))
        val result = LinkChecks.domainAgeResult("paypa1-secure.net", encrypted = true, known = known, now = now) {
            calls.incrementAndGet()
            young
        }
        assertEquals(1, calls.get())
        assertEquals(LinkChecks.checkDomainAge("paypa1-secure.net", young, true, now), result)
        assertEquals(Verdict.Danger, result.verdict)

        val failed = LinkChecks.domainAgeResult("unknown.example", encrypted = true, known = known, now = now) { null }
        assertEquals(Verdict.Warning, failed.verdict)
    }

    @Test
    fun aLookalikeOfAKnownDomainIsStillChecked() = runTest {
        val calls = AtomicInteger()
        LinkChecks.domainAgeResult("g00gle.com", encrypted = true, known = known, now = now) {
            calls.incrementAndGet()
            null
        }
        assertEquals(1, calls.get())
    }
}
