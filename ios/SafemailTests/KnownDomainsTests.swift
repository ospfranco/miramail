import Foundation
import Testing
@testable import Safemail

struct KnownDomainsTests {
    private let known = KnownDomains.shared
    private let suffixes = PublicSuffixList.shared

    @Test func parsesTheFileFormat() {
        let list = KnownDomains(contents: "# a comment\n\nGoogle.com\n  allianz.de  \r\nadmin.ch\ngoogle.com\n#spiegel.de\n")
        #expect(list.domains == ["google.com", "allianz.de", "admin.ch"])
        #expect(list.contains("GOOGLE.com"))
        #expect(!list.contains("spiegel.de"))
        #expect(KnownDomains(contents: "").domains.isEmpty)
    }

    @Test func recognizesKnownDomainsAndEverythingUnderThem() {
        for host in [
            "google.com", "www.google.com", "mail.google.com", "WWW.ALLIANZ.DE", "login.sparkasse.de", "www.youtube.com",
            "m.facebook.com", "www.amazon.co.uk", "www.bbc.co.uk", "www.orf.at", "www.bmf.gv.at", "finanzonline.bmf.gv.at",
            "oesterreich.gv.at", "www.admin.ch", "bag.admin.ch", "tickets.sbb.ch", "www.deutsche-bank.de", "google.com.",
        ] {
            #expect(known.includes(host: host, publicSuffixes: suffixes), "\(host)")
        }
    }

    @Test func lookalikesAndImpostersAreNeverKnown() {
        for host in [
            "g00gle.com", "google.com.evil.net", "evil-google.com", "login-google.com", "googIe.com", "allianz-versicherung.de",
            "allianz.de.evil.com", "sparkasse-login.de", "sparkasse.de.kunden-portal.net", "paypa1.com", "deutsche-bank.de-login.com",
            "example.com", "co.uk", "gv.at", "localhost", "", ".",
        ] {
            #expect(!known.includes(host: host, publicSuffixes: suffixes), "\(host)")
        }
    }

    @Test func platformsWhereAnyoneCanPublishAreNeverKnown() {
        for platform in [
            "blogspot.com", "github.io", "gitlab.io", "herokuapp.com", "netlify.app", "vercel.app", "pages.dev", "workers.dev",
            "web.app", "firebaseapp.com", "appspot.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com",
            "googleusercontent.com", "googleapis.com", "sharepoint.com", "atlassian.net", "wordpress.com", "wixsite.com",
            "weebly.com", "myshopify.com", "medium.com", "tumblr.com", "bit.ly", "tinyurl.com", "t.co", "goo.gl",
        ] {
            #expect(!known.domains.contains(platform), "\(platform) must not be listed")
            #expect(!known.includes(host: "login-paypal.\(platform)", publicSuffixes: suffixes), "\(platform)")
        }
    }

    @Test func theShippedListIsLargeAndEveryEntryIsARegistrableDomain() {
        #expect(known.domains.count > 800)
        for entry in known.domains {
            #expect(entry == entry.lowercased() && !entry.hasPrefix(".") && !entry.hasSuffix("."), "\(entry)")
            #expect(suffixes.registrableDomain(of: entry) == entry, "\(entry) is not its own registrable domain")
        }
    }

    @Test func coversTheBigNamesAndGermanSpeakingCountriesInDepth() {
        let global = ["google.com", "youtube.com", "facebook.com", "microsoft.com", "apple.com", "amazon.com", "paypal.com", "linkedin.com", "netflix.com", "instagram.com", "whatsapp.com"]
        let germany = [
            "allianz.de", "sparkasse.de", "deutsche-bank.de", "commerzbank.de", "ing.de", "dkb.de", "postbank.de", "telekom.de",
            "t-online.de", "web.de", "gmx.de", "spiegel.de", "zeit.de", "bild.de", "tagesschau.de", "bahn.de", "lufthansa.com",
            "dhl.de", "otto.de", "zalando.de", "mediamarkt.de", "bmw.com", "siemens.com", "sap.com", "bund.de", "elster.de",
            "arbeitsagentur.de", "barmer.de", "tk.de", "check24.de",
        ]
        let austria = ["orf.at", "derstandard.at", "willhaben.at", "erstebank.at", "raiffeisen.at", "a1.net", "oebb.at", "post.at", "oesterreich.gv.at", "wien.gv.at", "uniqa.at"]
        let switzerland = ["admin.ch", "sbb.ch", "post.ch", "ubs.com", "postfinance.ch", "swisscom.ch", "nzz.ch", "srf.ch", "migros.ch", "coop.ch", "galaxus.ch", "zh.ch"]
        for domain in global + germany + austria + switzerland {
            #expect(known.contains(domain), "\(domain) is missing")
        }
    }
}

struct DomainAgeLookupTests {
    private final class Calls: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func hit() { lock.withLock { count += 1 } }
        var value: Int { lock.withLock { count } }
    }

    private let known = KnownDomains(contents: "google.com\nallianz.de\n")
    private let now = date("2026-10-02T12:00:00Z")

    @Test func aWellKnownDomainSaysSoAndIsNeverLookedUp() async {
        let calls = Calls()
        let result = await LinkChecks.domainAgeResult(domain: "google.com", encrypted: true, known: known, now: now) {
            calls.hit()
            return nil
        }
        #expect(result == LinkCheck(kind: .domainAge, verdict: .safe, detail: "google.com is a well-known domain, so its age isn't checked."))
        #expect(calls.value == 0)
    }

    @Test func aWellKnownDomainIsSkippedForUnencryptedLinksToo() async {
        let calls = Calls()
        let result = await LinkChecks.domainAgeResult(domain: "allianz.de", encrypted: false, known: known, now: now) {
            calls.hit()
            return nil
        }
        #expect(result.verdict == .safe)
        #expect(calls.value == 0)
    }

    @Test func anyOtherDomainIsLookedUpOnceAndJudgedAsBefore() async {
        let calls = Calls()
        let young = CertificateHistory(firstIssued: now.addingTimeInterval(-3 * 86_400))
        let result = await LinkChecks.domainAgeResult(domain: "paypa1-secure.net", encrypted: true, known: known, now: now) {
            calls.hit()
            return young
        }
        #expect(calls.value == 1)
        #expect(result == LinkChecks.checkDomainAge(domain: "paypa1-secure.net", history: young, encrypted: true, now: now))
        #expect(result.verdict == .danger)

        let failed = await LinkChecks.domainAgeResult(domain: "unknown.example", encrypted: true, known: known, now: now) { nil }
        #expect(failed.verdict == .warning)
    }

    @Test func aLookalikeOfAKnownDomainIsStillChecked() async {
        let calls = Calls()
        _ = await LinkChecks.domainAgeResult(domain: "g00gle.com", encrypted: true, known: known, now: now) {
            calls.hit()
            return nil
        }
        #expect(calls.value == 1)
    }
}
