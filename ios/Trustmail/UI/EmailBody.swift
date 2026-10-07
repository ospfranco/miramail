import SwiftUI
import WebKit

/// Builds the HTML shown in the email viewer. Email content is untrusted.
enum EmailHtml {
    /// By default the CSP blocks every script in the email itself; with "Allow JavaScript in emails"
    /// on, inline and https scripts are permitted. Our own script is injected by the native web view
    /// in a separate content world, which the page's CSP does not apply to.
    static func csp(allowScripts: Bool) -> String {
        let base = "default-src 'none'; img-src https: http: data: cid:; style-src 'unsafe-inline' https:; font-src https: data:"
        return allowScripts ? base + "; script-src 'unsafe-inline' https:" : base
    }

    static var badgeCss: String {
        """
        .tm-badge {
          display: inline-flex; align-items: center; justify-content: center;
          width: 14px; height: 14px; margin-left: 3px; border-radius: 7px;
          font: bold 10px/1 -apple-system, sans-serif; color: #fff; vertical-align: middle;
          text-decoration: none;
        }
        \(Verdict.allCases.map { ".tm-\($0.rawValue) { background: #\($0.hexColor); }" }.joined(separator: "\n"))
        """
    }

    /// Elements that hold an email's footer. A link inside one is reported with `inFooter`.
    static let footerSelector = #"footer, [role="contentinfo"], [id*="footer" i], [class*="footer" i]"#

    /// Collects links and posts them to the app; exposes `__trustmailApply` to draw badges.
    static let injectedScript = """
    (function () {
      var post = function (message) { window.webkit.messageHandlers.trustmail.postMessage(message); };
      var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
      document.addEventListener('click', function (e) {
        var a = e.target.closest && e.target.closest('a[href]');
        if (!a) return;
        e.preventDefault();
        post({ type: 'click', url: a.getAttribute('href').trim() });
      }, true);
      window.__trustmailApply = function (results) {
        anchors.forEach(function (a) {
          var result = results[a.getAttribute('href').trim()];
          if (!result) return;
          var next = a.nextElementSibling;
          if (next && next.classList.contains('tm-badge')) next.remove();
          var badge = document.createElement('span');
          badge.className = 'tm-badge tm-' + result.verdict;
          badge.textContent = result.glyph;
          a.insertAdjacentElement('afterend', badge);
        });
      };
      post({
        type: 'links',
        links: anchors.map(function (a) {
          return { url: a.getAttribute('href').trim(), text: (a.textContent || '').trim(), inFooter: !!a.closest('\(footerSelector)') };
        }),
      });
    })();
    """

    static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }

    static func textToHtml(_ text: String) -> String {
        let linked = escape(text).replacing(/\bhttps?:\/\/[^\s<>"]+[^\s<>".,;:!?)\]]/) { match in
            "<a href=\"\(match.output)\">\(match.output)</a>"
        }
        return "<div style=\"white-space: pre-wrap; font-family: -apple-system, sans-serif;\">\(linked)</div>"
    }

    static func document(body: String, type: BodyType, allowScripts: Bool) -> String {
        """
        <!doctype html>
        <html><head>
        <meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="\(csp(allowScripts: allowScripts))">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body { margin: 12px; background: #fff; word-wrap: break-word; } img { max-width: 100%; height: auto; } \(badgeCss)</style>
        </head><body>\(type == .html ? body : textToHtml(body))</body></html>
        """
    }

    /// The email as shown to `LinkInspector`: nothing in it may run or load.
    static func inspectionDocument(body: String, type: BodyType) -> String {
        """
        <!doctype html>
        <html><head>
        <meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'">
        </head><body>\(type == .html ? body : textToHtml(body))</body></html>
        """
    }

    /// JavaScript that draws a verdict badge next to every scanned link.
    static func applyBadgesScript(_ links: [ScannedLink]) -> String {
        var results: [String: [String: String]] = [:]
        for link in links { results[link.url] = ["verdict": link.verdict.rawValue, "glyph": link.verdict.glyph] }
        let data = (try? JSONSerialization.data(withJSONObject: results)) ?? Data("{}".utf8)
        return "window.__trustmailApply(\(String(decoding: data, as: UTF8.self)));"
    }
}

struct EmailBodyView: UIViewRepresentable {
    let body: String
    let bodyType: BodyType
    /// Scan results; badges are drawn next to each link once these arrive.
    let scannedLinks: [ScannedLink]?
    let onLinksFound: ([FoundLink]) -> Void
    let onLinkPress: (String) -> Void
    /// Whether scripts inside the email may run (the "Allow JavaScript in emails" setting).
    let allowJavaScript: Bool
    /// The body scrolled: how far down, and how far it can scroll in all (points).
    var onScroll: (_ offset: CGFloat, _ scrollable: CGFloat) -> Void = { _, _ in }

    // Runs our script in its own world so nothing in the email can see or call it.
    static let world = WKContentWorld.world(name: "trustmail")

    /// The web view configuration: our scanning script and message channel, and whether the email's own scripts may run.
    static func makeConfiguration(allowJavaScript: Bool, handler: WKScriptMessageHandler) -> WKWebViewConfiguration {
        let config = WKWebViewConfiguration()
        // Scripts we inject and run ourselves are not affected by this switch, only the email's own.
        config.defaultWebpagePreferences.allowsContentJavaScript = allowJavaScript
        config.userContentController.add(handler, contentWorld: world, name: "trustmail")
        config.userContentController.addUserScript(
            WKUserScript(source: EmailHtml.injectedScript, injectionTime: .atDocumentEnd, forMainFrameOnly: true, in: world)
        )
        return config
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let config = Self.makeConfiguration(allowJavaScript: allowJavaScript, handler: context.coordinator)

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .white
        webView.scrollView.backgroundColor = .white
        webView.loadHTMLString(EmailHtml.document(body: body, type: bodyType, allowScripts: allowJavaScript), baseURL: nil)
        context.coordinator.webView = webView
        context.coordinator.body = body
        context.coordinator.bodyType = bodyType
        context.coordinator.allowJavaScript = allowJavaScript
        context.coordinator.scrollObservation = webView.scrollView.observe(\.contentOffset) { [weak coordinator = context.coordinator] scrollView, _ in
            MainActor.assumeIsolated { coordinator?.scrolled(scrollView) }
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let coordinator = context.coordinator
        coordinator.onLinksFound = onLinksFound
        coordinator.onLinkPress = onLinkPress
        coordinator.onScroll = onScroll
        if let scannedLinks, scannedLinks != coordinator.appliedLinks {
            coordinator.appliedLinks = scannedLinks
            webView.evaluateJavaScript(EmailHtml.applyBadgesScript(scannedLinks), in: nil, in: Self.world, completionHandler: nil)
        }
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "trustmail", contentWorld: world)
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        weak var webView: WKWebView?
        var onLinksFound: ([FoundLink]) -> Void = { _ in }
        var onLinkPress: (String) -> Void = { _ in }
        var onScroll: (CGFloat, CGFloat) -> Void = { _, _ in }
        var scrollObservation: NSKeyValueObservation?
        var appliedLinks: [ScannedLink]?
        var body = ""
        var bodyType = BodyType.html
        var allowJavaScript = false

        func scrolled(_ scrollView: UIScrollView) {
            let insets = scrollView.adjustedContentInset
            onScroll(
                scrollView.contentOffset.y + insets.top,
                scrollView.contentSize.height + insets.top + insets.bottom - scrollView.bounds.height
            )
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
            switch type {
            case "links":
                let links = (body["links"] as? [[String: Any]] ?? []).compactMap { item -> FoundLink? in
                    guard let url = item["url"] as? String else { return nil }
                    return FoundLink(url: url, text: item["text"] as? String ?? "", inFooter: item["inFooter"] as? Bool ?? false)
                }
                if allowJavaScript {
                    onLinksFound(links)
                } else {
                    // Scripting is off in this view, so WebKit hid the `javascript:` links from it (see LinkInspector).
                    let (body, type) = (self.body, bodyType)
                    Task { onLinksFound(await LinkInspector.links(inBody: body, type: type)) }
                }
            case "click":
                if let url = body["url"] as? String { onLinkPress(url) }
            default:
                break
            }
        }

        // Link taps are caught in the page and sent to the app. Block every other navigation
        // (meta refresh, form posts) so the email can't leave the viewer.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
        ) {
            let scheme = navigationAction.request.url?.scheme ?? "about"
            decisionHandler(scheme == "about" || scheme == "data" ? .allow : .cancel)
        }
    }
}
