import { useEffect, useMemo, useRef } from 'react';
import { StyleSheet } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';

import type { FoundLink, ScannedLink } from '@/lib/security';
import { VerdictColor, VerdictGlyph } from '@/lib/security';

type Props = {
  body: string;
  bodyType: 'html' | 'text';
  /** Scan results; badges are drawn next to each link once these arrive. */
  scannedLinks: ScannedLink[] | null;
  onLinksFound: (links: FoundLink[]) => void;
  onLinkPress: (url: string) => void;
};

// Email content is untrusted: the CSP blocks every script in the email itself.
// Our injected script still runs because the native WebView injects it outside the page's CSP.
const CSP =
  "default-src 'none'; img-src https: http: data: cid:; style-src 'unsafe-inline' https:; font-src https: data:";

const BADGE_CSS = `
  .sm-badge {
    display: inline-flex; align-items: center; justify-content: center;
    width: 14px; height: 14px; margin-left: 3px; border-radius: 7px;
    font: bold 10px/1 -apple-system, sans-serif; color: #fff; vertical-align: middle;
    text-decoration: none;
  }
  ${Object.entries(VerdictColor)
    .map(([verdict, color]) => `.sm-${verdict} { background: ${color}; }`)
    .join('\n')}
`;

// Collects links, posts them to React Native, and exposes __safemailApply to draw badges.
const INJECTED_JS = `
(function () {
  var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
  document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[href]');
    if (!a) return;
    e.preventDefault();
    window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'click', url: a.getAttribute('href').trim() }));
  }, true);
  window.__safemailApply = function (results) {
    anchors.forEach(function (a) {
      var result = results[a.getAttribute('href').trim()];
      if (!result) return;
      var next = a.nextElementSibling;
      if (next && next.classList.contains('sm-badge')) next.remove();
      var badge = document.createElement('span');
      badge.className = 'sm-badge sm-' + result.verdict;
      badge.textContent = result.glyph;
      a.insertAdjacentElement('afterend', badge);
    });
  };
  window.ReactNativeWebView.postMessage(JSON.stringify({
    type: 'links',
    links: anchors.map(function (a) {
      return { url: a.getAttribute('href').trim(), text: (a.textContent || '').trim() };
    }),
  }));
})();
true;
`;

function escapeHtml(s: string) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function textToHtml(text: string) {
  const linked = escapeHtml(text).replace(
    /\bhttps?:\/\/[^\s<>"]+[^\s<>".,;:!?)\]]/g,
    (url) => `<a href="${url}">${url}</a>`,
  );
  return `<div style="white-space: pre-wrap; font-family: -apple-system, sans-serif;">${linked}</div>`;
}

export function EmailBody({ body, bodyType, scannedLinks, onLinksFound, onLinkPress }: Props) {
  const webView = useRef<WebView>(null);

  const html = useMemo(
    () => `<!doctype html>
<html><head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${CSP}">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body { margin: 12px; background: #fff; word-wrap: break-word; } img { max-width: 100%; height: auto; } ${BADGE_CSS}</style>
</head><body>${bodyType === 'html' ? body : textToHtml(body)}</body></html>`,
    [body, bodyType],
  );

  useEffect(() => {
    if (!scannedLinks) return;
    const results = Object.fromEntries(
      scannedLinks.map((l) => [l.url, { verdict: l.verdict, glyph: VerdictGlyph[l.verdict] }]),
    );
    webView.current?.injectJavaScript(`window.__safemailApply(${JSON.stringify(results)}); true;`);
  }, [scannedLinks]);

  const handleMessage = (event: WebViewMessageEvent) => {
    const message = JSON.parse(event.nativeEvent.data);
    if (message.type === 'links') onLinksFound(message.links);
    if (message.type === 'click') onLinkPress(message.url);
  };

  return (
    <WebView
      ref={webView}
      style={styles.webView}
      originWhitelist={['*']}
      source={{ html }}
      injectedJavaScript={INJECTED_JS}
      onMessage={handleMessage}
      setSupportMultipleWindows={false}
      // Link taps are caught in the page and sent via onMessage. Block every other
      // navigation (meta refresh, form posts) so the email can't leave the viewer.
      onShouldStartLoadWithRequest={(request) =>
        request.url.startsWith('about:') || request.url.startsWith('data:')
      }
    />
  );
}

const styles = StyleSheet.create({
  webView: { flex: 1, backgroundColor: '#fff' },
});
