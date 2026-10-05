import Foundation
import Testing
import WebKit
@testable import Trustmail

/// Runs the real web view configuration against an email, the way the app does.
@MainActor
private final class WebProbe: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    var links: [FoundLink]?
    var webView: WKWebView!
    private var loaded: CheckedContinuation<Void, Never>?

    func load(html: String, allowJavaScript: Bool) async {
        let config = EmailBodyView.makeConfiguration(allowJavaScript: allowJavaScript, handler: self)
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 320, height: 640), configuration: config)
        webView.navigationDelegate = self
        await withCheckedContinuation { continuation in
            loaded = continuation
            webView.loadHTMLString(EmailHtml.document(body: html, type: .html, allowScripts: allowJavaScript), baseURL: nil)
        }
        // The scan script posts the links shortly after the page is ready.
        for _ in 0..<100 where links == nil { try? await Task.sleep(for: .milliseconds(50)) }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loaded?.resume()
        loaded = nil
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], body["type"] as? String == "links" else { return }
        links = (body["links"] as? [[String: Any]] ?? []).map { FoundLink(url: $0["url"] as? String ?? "", text: $0["text"] as? String ?? "") }
    }

    /// Reads the page's DOM (shared by every content world).
    func text(of id: String) async -> String? {
        try? await webView.evaluateJavaScript("document.getElementById('\(id)').textContent", in: nil, contentWorld: EmailBodyView.world) as? String
    }
}

@MainActor
struct EmailWebViewTests {
    private let html = """
    <p>Please <a href="http://login.example.net/verify">paypal.com/login</a></p>
    <p><a href="https://bit.ly/abc">Fix my account</a></p>
    <p><a href="javascript:alert(1)">Unsubscribe</a></p>
    <p id="js-status">blocked</p>
    <script>document.getElementById('js-status').textContent = 'running'</script>
    """

    private let expectedLinks = [
        FoundLink(url: "http://login.example.net/verify", text: "paypal.com/login"),
        FoundLink(url: "https://bit.ly/abc", text: "Fix my account"),
        FoundLink(url: "javascript:alert(1)", text: "Unsubscribe"),
    ]

    @Test func scriptsInTheEmailAreBlockedByDefault() async {
        let probe = WebProbe()
        await probe.load(html: html, allowJavaScript: false)
        #expect(await probe.text(of: "js-status") == "blocked")
    }

    @Test func scriptsInTheEmailRunWhenAllowed() async {
        let probe = WebProbe()
        await probe.load(html: html, allowJavaScript: true)
        #expect(await probe.text(of: "js-status") == "running")
    }

    /// Canary: WebKit deletes the href of `javascript:` links when scripting is off. If this ever
    /// starts failing, `LinkInspector` is no longer needed.
    @Test func webKitHidesJavaScriptLinksFromAViewWithScriptingOff() async {
        let probe = WebProbe()
        await probe.load(html: html, allowJavaScript: false)
        #expect(probe.links == Array(expectedLinks.prefix(2)))
    }

    /// Android finds the same links whichever way the setting is, and so must iOS.
    @Test func inspectorFindsEveryLinkWithoutRunningAnything() async {
        let links = await LinkInspector.links(inBody: html, type: .html)
        #expect(links == expectedLinks)
    }

    @Test func inspectorHandlesPlainTextEmails() async {
        let links = await LinkInspector.links(inBody: "Read https://example.com/a, thanks", type: .text)
        #expect(links == [FoundLink(url: "https://example.com/a", text: "https://example.com/a")])
    }

    @Test func everyLinkIsFoundWithJavaScriptOn() async {
        let probe = WebProbe()
        await probe.load(html: html, allowJavaScript: true)
        #expect(probe.links == expectedLinks)
    }
}
