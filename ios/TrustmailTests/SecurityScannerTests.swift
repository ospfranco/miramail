import Testing
@testable import Trustmail

struct SecurityScannerTests {
    @Test func parsesUrls() {
        #expect(SecurityScanner.parseUrl("https://Example.COM/path?q=1") == ParsedUrl(scheme: "https", host: "example.com", hasUserInfo: false))
        #expect(SecurityScanner.parseUrl("  http://example.com:8080/x ") == ParsedUrl(scheme: "http", host: "example.com", hasUserInfo: false))
        #expect(SecurityScanner.parseUrl("https://paypal.com@evil.com/") == ParsedUrl(scheme: "https", host: "evil.com", hasUserInfo: true))
        #expect(SecurityScanner.parseUrl("https://user:pw@evil.com:99/") == ParsedUrl(scheme: "https", host: "evil.com", hasUserInfo: true))
        #expect(SecurityScanner.parseUrl("mailto:a@b.com") == ParsedUrl(scheme: "mailto", host: "", hasUserInfo: false))
        #expect(SecurityScanner.parseUrl("http://[::1]:80/") == ParsedUrl(scheme: "http", host: "[::1]", hasUserInfo: false))
        #expect(SecurityScanner.parseUrl("/relative/path") == nil)
        #expect(SecurityScanner.parseUrl("not a url") == nil)
    }

    @Test func findsHostsInLinkText() {
        #expect(SecurityScanner.hostInText("paypal.com/login") == "paypal.com")
        #expect(SecurityScanner.hostInText(" https://www.Paypal.com ") == "paypal.com")
        #expect(SecurityScanner.hostInText("Click here") == nil)
        #expect(SecurityScanner.hostInText("Visit paypal.com today") == nil)
        #expect(SecurityScanner.hostInText("") == nil)
    }

    @Test func comparesSites() {
        #expect(SecurityScanner.sameSite("www.github.com", "github.com"))
        #expect(SecurityScanner.sameSite("mail.github.com", "github.com"))
        #expect(!SecurityScanner.sameSite("github.com", "github.com.evil.net"))
        #expect(!SecurityScanner.sameSite("paypal.com", "paypa1.com"))
    }

    @Test func detectsIpAddresses() {
        #expect(SecurityScanner.isIpAddress("192.168.0.1"))
        #expect(SecurityScanner.isIpAddress("[::1]"))
        #expect(!SecurityScanner.isIpAddress("1.2.3.example.com"))
        #expect(!SecurityScanner.isIpAddress("example.com"))
    }

    private func local(_ url: String, text: String = "") -> [LinkCheck] {
        let parsed = SecurityScanner.parseUrl(url)!
        return SecurityScanner.localChecks(link: FoundLink(url: url, text: text), scheme: parsed.scheme, host: parsed.host, hasUserInfo: parsed.hasUserInfo)
    }

    @Test func localChecksFlagTrickyLinks() {
        #expect(local("https://example.com/x", text: "Read more").isEmpty)
        #expect(local("https://example.com/x", text: "example.com").isEmpty)

        #expect(local("https://10.0.0.1/login") == [LinkCheck(kind: .linkFormat, verdict: .danger, detail: "Points to a raw IP address instead of a domain.")])
        #expect(local("https://paypal.com@evil.com/") == [LinkCheck(kind: .linkFormat, verdict: .danger, detail: "Hides the real destination behind an \"@\".")])
        #expect(local("https://evil.com/", text: "paypal.com/login") == [
            LinkCheck(kind: .displayText, verdict: .danger, detail: "Text shows paypal.com but the link goes to evil.com."),
        ])
        #expect(local("http://example.com") == [LinkCheck(kind: .encryption, verdict: .warning, detail: "Not encrypted (http).")])
        #expect(local("https://xn--pypal-4ve.com") == [LinkCheck(kind: .lookalikeDomain, verdict: .warning, detail: "Uses look-alike (punycode) characters in the domain.")])
        #expect(local("https://bit.ly/abc") == [LinkCheck(kind: .shortener, verdict: .warning, detail: "Shortened link hides the final destination.")])
    }

    @Test func dedupesLinksKeepingTheOneWithText() {
        let unique = SecurityScanner.uniqueLinks([
            FoundLink(url: "https://a.com", text: ""),
            FoundLink(url: "https://b.com", text: "B"),
            FoundLink(url: "https://a.com", text: "A"),
            FoundLink(url: "https://b.com", text: "other"),
        ])
        #expect(unique == [FoundLink(url: "https://a.com", text: "A"), FoundLink(url: "https://b.com", text: "B")])
    }

    @Test func scansLinksThatNeedNoNetwork() async {
        let scanned = await SecurityScanner.scanLinks([
            FoundLink(url: "mailto:a@b.com", text: "mail"),
            FoundLink(url: "tel:+123", text: "call"),
            FoundLink(url: "javascript:alert(1)", text: "x"),
            FoundLink(url: "http://10.0.0.1/a", text: "ip"),
            FoundLink(url: "/relative", text: "rel"),
        ])
        #expect(scanned.map(\.url) == ["mailto:a@b.com", "tel:+123", "javascript:alert(1)", "http://10.0.0.1/a", "/relative"])
        #expect(scanned.map(\.verdict) == [.safe, .safe, .danger, .danger, .warning])
        #expect(scanned[2].checks.first?.detail == "Uses a \"javascript:\" link, which can run code.")
        #expect(scanned[3].host == "10.0.0.1")
        #expect(scanned[4].checks.first?.detail == "Link could not be parsed.")
    }

    private func scanned(_ verdict: Verdict) -> ScannedLink {
        ScannedLink(url: "https://x.com/\(verdict.rawValue)", text: "", host: "x.com", verdict: verdict, checks: [])
    }

    @Test func evaluatesEmail() {
        let safeSender = [SecurityCheck(label: "Sender verified", detail: "", verdict: .safe)]

        let none = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: [])
        #expect(none.verdict == .safe)
        #expect(none.summary == "No problems found")
        #expect(none.checks.last == SecurityCheck(label: "No links", detail: "No problems found in links.", verdict: .safe))

        let one = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: [scanned(.safe)])
        #expect(one.checks.last?.label == "1 link scanned")

        let mixed = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: [scanned(.safe), scanned(.warning), scanned(.danger), scanned(.danger)])
        #expect(mixed.verdict == .danger)
        #expect(mixed.summary == "This email looks dangerous")
        #expect(mixed.checks.last == SecurityCheck(label: "4 links scanned", detail: "2 unsafe, 1 suspicious", verdict: .danger))

        let warnSender = [SecurityCheck(label: "First message from this sender", detail: "", verdict: .warning)]
        let warn = SecurityScanner.evaluateEmail(senderChecks: warnSender, links: [scanned(.safe)])
        #expect(warn.verdict == .warning)
        #expect(warn.summary == "Be careful with this email")
    }
}
