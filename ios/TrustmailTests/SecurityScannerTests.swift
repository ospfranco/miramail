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

    @Test func cleansUrls() {
        #expect(SecurityScanner.cleanUrl(" https://a.com/path?utm_source=x&id=1#top ") == "https://a.com/path")
        #expect(SecurityScanner.cleanUrl("https://a.com/#/route?x=1") == "https://a.com/")
        #expect(SecurityScanner.cleanUrl("paypal.com/login") == "paypal.com/login")
    }

    @Test func linkWarningShowsTheShownAndRealUrls() {
        let checks = [
            LinkCheck(kind: .displayText, verdict: .danger, detail: "Text shows paypal.com but the link goes to login.example.net."),
            LinkCheck(kind: .domainAge, verdict: .safe, detail: "Registered long ago."),
            LinkCheck(kind: .encryption, verdict: .warning, detail: "Not encrypted (http)."),
        ]
        let mismatch = ScannedLink(
            url: "http://login.example.net/verify?session=abc#x", text: "paypal.com/login?ref=mail",
            host: "login.example.net", verdict: .danger, checks: checks
        )
        // Why it's risky in plain words, unsafe problems first and one sentence per kind, then what to do.
        #expect(SecurityScanner.linkWarning(mismatch) == """
        Shown as: paypal.com/login
        Goes to: http://login.example.net/verify

        The link's text shows one website, but it takes you to another. Fake emails do this to look trustworthy.

        The connection to this website isn't secure, so anything you type there, like a password, could be seen by others.

        Don't open it unless you're sure where it goes, and never enter a password or personal details there.
        """)

        let shortener = ScannedLink(
            url: "https://bit.ly/abc?x=1", text: "Fix my account", host: "bit.ly", verdict: .warning,
            checks: [LinkCheck(kind: .shortener, verdict: .warning, detail: "Shortened link hides the final destination.")]
        )
        #expect(SecurityScanner.linkWarning(shortener) == "Goes to: https://bit.ly/abc\n\nThe link is shortened, so you can't see where it really goes before you open it.\n\nOnly open it if you trust where it goes.")
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

    @Test func aLinkIsInTheFooterOnlyIfEveryCopyIs() {
        let unique = SecurityScanner.uniqueLinks([
            FoundLink(url: "https://a.com", text: "", inFooter: true),
            FoundLink(url: "https://b.com", text: "B", inFooter: true),
            FoundLink(url: "https://a.com", text: "A", inFooter: false),
            FoundLink(url: "https://b.com", text: "", inFooter: true),
        ])
        #expect(unique == [FoundLink(url: "https://a.com", text: "A", inFooter: false), FoundLink(url: "https://b.com", text: "B", inFooter: true)])
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
        let safeSender = [SecurityCheck(.verified)]

        // Links are only mentioned when some are a problem.
        let none = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: [])
        #expect(none.verdict == .safe)
        #expect(none.summary == "No problems found")
        #expect(none.checks == safeSender)
        #expect(SecurityScanner.evaluateEmail(senderChecks: safeSender, links: [scanned(.safe)]).checks == safeSender)

        let mixed = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: [scanned(.safe), scanned(.warning), scanned(.danger), scanned(.danger)])
        #expect(mixed.verdict == .danger)
        #expect(mixed.summary == "This email looks dangerous")
        #expect(mixed.checks == safeSender + [SecurityCheck(.unsafe), SecurityCheck(.suspicious)])

        let warnSender = [SecurityCheck(.firstMessage)]
        let warn = SecurityScanner.evaluateEmail(senderChecks: warnSender, links: [scanned(.safe)])
        #expect(warn.verdict == .warning)
        #expect(warn.summary == "Be careful with this email")
        #expect(!warn.senderFailed)
        #expect(!mixed.senderFailed, "unsafe links alone don't mark the sender")
    }

    @Test func theHeaderShowsOnlyProblemsUnsafeFirst() {
        let evaluation = SecurityScanner.evaluateEmail(senderChecks: [SecurityCheck(.verified), SecurityCheck(.firstMessage)], links: [scanned(.danger)])
        #expect(evaluation.findings == [SecurityCheck(.unsafe), SecurityCheck(.firstMessage)])
        #expect(SecurityScanner.evaluateEmail(senderChecks: [SecurityCheck(.verified), SecurityCheck(.known)], links: [scanned(.safe)]).findings.isEmpty)
    }

    @Test func marksTheSenderOnlyWhenASenderCheckFails() {
        let failed = [
            SecurityCheck(.failed),
            SecurityCheck(.known),
        ]
        #expect(SecurityScanner.evaluateEmail(senderChecks: failed, links: []).senderFailed)
        let unverified = [SecurityCheck(.notVerified)]
        #expect(!SecurityScanner.evaluateEmail(senderChecks: unverified, links: []).senderFailed)
    }

    private func footer(_ verdict: Verdict) -> ScannedLink {
        ScannedLink(url: "https://x.com/footer/\(verdict.rawValue)", text: "", host: "x.com", verdict: verdict, checks: [], inFooter: true)
    }

    @Test func ignoresSuspiciousFooterLinksOnlyWhenAsked() {
        let safeSender = [SecurityCheck(.verified)]
        let links = [scanned(.safe), footer(.warning)]

        let counted = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: links)
        #expect(counted.verdict == .warning)
        #expect(counted.checks == safeSender + [SecurityCheck(.suspicious)])

        let ignored = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: links, ignoreFooterLinks: true)
        #expect(ignored.verdict == .safe)
        #expect(ignored.summary == "No problems found")
        #expect(ignored.checks == safeSender)

        // An unsafe footer link always counts.
        let unsafe = SecurityScanner.evaluateEmail(senderChecks: safeSender, links: [footer(.warning), footer(.danger), scanned(.warning)], ignoreFooterLinks: true)
        #expect(unsafe.verdict == .danger)
        #expect(unsafe.checks == safeSender + [SecurityCheck(.unsafe), SecurityCheck(.suspicious)])
    }

    /// Every card in the security panel says, in plain words, what was found and what to do.
    @Test func describesEveryCheckInPlainWords() {
        #expect(SenderCheckLabel.allCases.map { "\($0.rawValue): \($0.description)" } == [
            "Sender verified: Gmail confirmed that this email really comes from the address it shows.",
            "Sender not verified: Gmail couldn't confirm that this email really comes from the address it shows. That's often harmless, but someone could be pretending to be this sender. Be careful if it asks you to do something or to share information.",
            "Sender verification failed: This email pretends to come from an address that didn't send it, so it's probably a fake. Don't open its links or reply with personal details. If it claims to be from someone you know, contact them another way.",
            "Known sender: You've exchanged emails with this address before.",
            "First message from this sender: This is the first email you get from this address. If it asks you to do something or to share information, be careful before you continue.",
            "Sender history unknown: Trustmail couldn't check whether this address has emailed you before, for example because you're offline. Be careful if it asks you to do something or to share information.",
        ])
        #expect(LinkFindingLabel.allCases.map { "\($0.rawValue): \($0.description)" } == [
            "Unsafe links: Some links in this email may lead to a fake or harmful website. Don't open the links marked with a red ✕.",
            "Suspicious links: Some links in this email look unusual. Tap one marked with a yellow ! to see why before anything opens.",
        ])
        #expect(SenderCheckLabel.allCases.map(\.verdict) == [.safe, .warning, .danger, .safe, .warning, .warning])
        #expect(LinkFindingLabel.allCases.map(\.verdict) == [.danger, .warning])
    }
}
