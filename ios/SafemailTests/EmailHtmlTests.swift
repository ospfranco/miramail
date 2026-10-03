import Foundation
import Testing
@testable import Safemail

struct EmailHtmlTests {
    @Test func escapesHtml() {
        #expect(EmailHtml.escape("<a href=\"x\">&</a>") == "&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;")
    }

    @Test func linkifiesPlainText() {
        let html = EmailHtml.textToHtml("See https://example.com/a?b=1, or http://x.org. <script>")
        #expect(html.contains("<a href=\"https://example.com/a?b=1\">https://example.com/a?b=1</a>,"))
        #expect(html.contains("<a href=\"http://x.org\">http://x.org</a>."))
        #expect(html.contains("&lt;script&gt;"))
        #expect(!html.contains("<script>"))
    }

    @Test func documentBlocksScriptsAndKeepsBodies() {
        let htmlDoc = EmailHtml.document(body: "<p>Hello <a href=\"https://a.com\">a</a></p>", type: .html, allowScripts: false)
        #expect(htmlDoc.contains("Content-Security-Policy"))
        #expect(htmlDoc.contains("default-src 'none'"))
        #expect(htmlDoc.contains("<body><p>Hello <a href=\"https://a.com\">a</a></p></body>"))
        #expect(htmlDoc.contains(".sm-danger { background: #E5484D; }"))

        let textDoc = EmailHtml.document(body: "a < b", type: .text, allowScripts: false)
        #expect(textDoc.contains("a &lt; b"))
        #expect(textDoc.contains("white-space: pre-wrap"))
    }

    @Test func contentSecurityPolicyAllowsScriptsOnlyWhenEnabled() {
        #expect(!EmailHtml.csp(allowScripts: false).contains("script-src"))
        #expect(EmailHtml.csp(allowScripts: false).contains("default-src 'none'"))
        #expect(EmailHtml.csp(allowScripts: true).contains("script-src 'unsafe-inline' https:"))
        #expect(EmailHtml.csp(allowScripts: true).contains("default-src 'none'"))
        #expect(EmailHtml.document(body: "x", type: .html, allowScripts: true).contains("script-src"))
        #expect(!EmailHtml.document(body: "x", type: .html, allowScripts: false).contains("script-src"))
    }

    @Test func badgeScriptCarriesVerdictsByUrl() throws {
        let links = [
            ScannedLink(url: "https://a.com", text: "", host: "a.com", verdict: .safe, checks: []),
            ScannedLink(url: "http://b.com/?q=\"x\"", text: "", host: "b.com", verdict: .danger, checks: []),
        ]
        let script = EmailHtml.applyBadgesScript(links)
        #expect(script.hasPrefix("window.__safemailApply("))
        let json = script.dropFirst("window.__safemailApply(".count).dropLast(2)
        let parsed = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: [String: String]]
        #expect(parsed["https://a.com"] == ["verdict": "safe", "glyph": "✓"])
        #expect(parsed["http://b.com/?q=\"x\""] == ["verdict": "danger", "glyph": "✕"])
    }
}
