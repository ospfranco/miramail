package com.ospfranco.trustmail.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ospfranco.trustmail.mail.BodyType
import com.ospfranco.trustmail.security.FoundLink
import com.ospfranco.trustmail.security.ScannedLink
import com.ospfranco.trustmail.security.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Builds the HTML shown in the email viewer. Email content is untrusted. */
object EmailHtml {
    /**
     * By default the CSP blocks every script in the email itself; with "Allow JavaScript in emails"
     * on, inline and https scripts are permitted. Our own script is evaluated by the native WebView
     * outside the page, which the page's CSP does not apply to.
     */
    fun csp(allowScripts: Boolean): String {
        val base = "default-src 'none'; img-src https: http: data: cid:; style-src 'unsafe-inline' https:; font-src https: data:"
        return if (allowScripts) "$base; script-src 'unsafe-inline' https:" else base
    }

    val badgeCss: String
        get() = """
            .tm-badge {
              display: inline-flex; align-items: center; justify-content: center;
              width: 14px; height: 14px; margin-left: 3px; border-radius: 7px;
              font: bold 10px/1 -apple-system, sans-serif; color: #fff; vertical-align: middle;
              text-decoration: none;
            }
            ${Verdict.entries.joinToString("\n") { ".tm-${it.id} { background: #${it.hexColor}; }" }}
        """.trimIndent()

    /** Elements that hold an email's footer. A link inside one is reported with `inFooter`. */
    const val FOOTER_SELECTOR = """footer, [role="contentinfo"], [id*="footer" i], [class*="footer" i]"""

    /**
     * Collects links and posts them to the app; exposes `__trustmailApply` to draw badges.
     * Every message carries [token], which only this script knows, so a script in the email (when
     * scripts are allowed) can't forge a message through the bridge, which the page can also reach.
     */
    fun injectedScript(token: String) = """
        (function () {
          var post = function (message) { message.token = '$token'; TrustmailBridge.postMessage(JSON.stringify(message)); };
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
              return { url: a.getAttribute('href').trim(), text: (a.textContent || '').trim(), inFooter: !!a.closest('$FOOTER_SELECTOR') };
            }),
          });
        })();
    """.trimIndent()

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val urlInText = Regex("""\bhttps?://[^\s<>"]+[^\s<>".,;:!?)\]]""")

    fun textToHtml(text: String): String {
        val linked = escape(text).replace(urlInText) { "<a href=\"${it.value}\">${it.value}</a>" }
        return "<div style=\"white-space: pre-wrap; font-family: -apple-system, sans-serif;\">$linked</div>"
    }

    fun document(body: String, type: BodyType, allowScripts: Boolean): String = """
        <!doctype html>
        <html><head>
        <meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="${csp(allowScripts)}">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body { margin: 12px; background: #fff; word-wrap: break-word; } img { max-width: 100%; height: auto; } $badgeCss</style>
        </head><body>${if (type == BodyType.Html) body else textToHtml(body)}</body></html>
    """.trimIndent()

    /** JavaScript that draws a verdict badge next to every scanned link. */
    fun applyBadgesScript(links: List<ScannedLink>): String {
        val results = buildJsonObject {
            for (link in links) {
                put(
                    link.url,
                    buildJsonObject {
                        put("verdict", link.verdict.id)
                        put("glyph", link.verdict.glyph)
                    },
                )
            }
        }
        return "window.__trustmailApply($results);"
    }

    /** What the injected script posts back: the links found, or the URL of a tapped link. */
    sealed interface Message {
        data class Links(val links: List<FoundLink>) : Message
        data class Click(val url: String) : Message
    }

    fun parseMessage(json: String, token: String): Message? = try {
        val obj: JsonObject = Json.parseToJsonElement(json).jsonObject
        if (obj["token"]?.jsonPrimitive?.content != token) {
            null
        } else when (obj["type"]?.jsonPrimitive?.content) {
            "links" -> Message.Links(
                (obj["links"]?.jsonArray ?: JsonArray(emptyList())).map {
                    val item = it.jsonObject
                    FoundLink(
                        item["url"]!!.jsonPrimitive.content,
                        item["text"]?.jsonPrimitive?.content ?: "",
                        item["inFooter"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                },
            )
            "click" -> Message.Click(obj["url"]!!.jsonPrimitive.content)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private class Bridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(json: String) = onMessage(json)
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun EmailBodyView(
    body: String,
    bodyType: BodyType,
    /** Scan results; badges are drawn next to each link once these arrive. */
    scannedLinks: List<ScannedLink>?,
    onLinksFound: (List<FoundLink>) -> Unit,
    onLinkPress: (String) -> Unit,
    /** Whether scripts inside the email may run (the "Allow JavaScript in emails" setting). */
    allowJavaScript: Boolean,
    modifier: Modifier = Modifier,
) {
    val token = remember { UUID.randomUUID().toString() }
    val currentOnLinksFound = rememberUpdatedState(onLinksFound)
    val currentOnLinkPress = rememberUpdatedState(onLinkPress)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.WHITE)
                // A WebView can't run only our scripts: JavaScript has to be on for them. Whether the email's
                // own scripts run is decided by the CSP (see EmailHtml.csp) and the "Allow JavaScript" setting.
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportMultipleWindows(false)

                addJavascriptInterface(
                    Bridge { json ->
                        post {
                            when (val message = EmailHtml.parseMessage(json, token)) {
                                is EmailHtml.Message.Links -> currentOnLinksFound.value(message.links)
                                is EmailHtml.Message.Click -> currentOnLinkPress.value(message.url)
                                null -> {}
                            }
                        }
                    },
                    "TrustmailBridge",
                )

                webViewClient = object : WebViewClient() {
                    private var injected = false

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        injected = false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (injected) return
                        injected = true
                        view.evaluateJavascript(EmailHtml.injectedScript(token), null)
                    }

                    // Link taps are caught in the page and sent to the app. Block every other
                    // navigation (meta refresh, form posts) so the email can't leave the viewer.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }

                loadDataWithBaseURL(null, EmailHtml.document(body, bodyType, allowJavaScript), "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            if (scannedLinks != null && webView.tag != scannedLinks) {
                webView.tag = scannedLinks
                webView.evaluateJavascript(EmailHtml.applyBadgesScript(scannedLinks), null)
            }
        },
        onRelease = { webView ->
            webView.removeJavascriptInterface("TrustmailBridge")
            webView.destroy()
        },
    )
}
