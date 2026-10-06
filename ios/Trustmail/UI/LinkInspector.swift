import Foundation
import WebKit

/// Finds the links in an email without showing it.
///
/// With "Allow JavaScript in emails" off, the visible web view runs with scripting disabled, and WebKit
/// then deletes the `href` of every `javascript:` link while parsing, so those links could never be
/// reported. Android keeps them. To report the same links, the email is also loaded here with scripting
/// enabled; the CSP blocks every script and every resource, so nothing in the email runs or is fetched.
@MainActor
final class LinkInspector: NSObject, WKNavigationDelegate {
    private var continuation: CheckedContinuation<[FoundLink], Never>?
    private var webView: WKWebView?
    // Keeps the inspector alive until the page has loaded.
    private var retainSelf: LinkInspector?

    /// Same selection as the script injected into the visible web view.
    static let collectScript = """
    JSON.stringify(Array.prototype.slice.call(document.querySelectorAll('a[href]')).map(function (a) {
      return { url: a.getAttribute('href').trim(), text: (a.textContent || '').trim(), inFooter: !!a.closest('\(EmailHtml.footerSelector)') };
    }))
    """

    static func links(inBody body: String, type: BodyType) async -> [FoundLink] {
        await LinkInspector().inspect(body: body, type: type)
    }

    private func inspect(body: String, type: BodyType) async -> [FoundLink] {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        self.webView = webView
        retainSelf = self
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            webView.loadHTMLString(EmailHtml.inspectionDocument(body: body, type: type), baseURL: nil)
        }
    }

    private func finish(_ links: [FoundLink]) {
        continuation?.resume(returning: links)
        continuation = nil
        webView?.navigationDelegate = nil
        webView = nil
        retainSelf = nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.evaluateJavaScript(Self.collectScript, in: nil, in: EmailBodyView.world) { [weak self] result in
            var links: [FoundLink] = []
            if case .success(let value) = result, let json = value as? String,
               let items = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]] {
                links = items.map {
                    FoundLink(url: $0["url"] as? String ?? "", text: $0["text"] as? String ?? "", inFooter: $0["inFooter"] as? Bool ?? false)
                }
            }
            Task { @MainActor in self?.finish(links) }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finish([]) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { finish([]) }
}
