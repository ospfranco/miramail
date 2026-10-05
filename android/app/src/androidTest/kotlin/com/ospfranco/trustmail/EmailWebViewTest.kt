package com.ospfranco.trustmail

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ospfranco.trustmail.mail.BodyType
import com.ospfranco.trustmail.security.FoundLink
import com.ospfranco.trustmail.ui.EmailHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the real WebView the way the app does: our scan script, a bridge, and the email's own scripts. */
@RunWith(AndroidJUnit4::class)
class EmailWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val token = "test-token"

    private class Probe(private val token: String) {
        @Volatile var links: List<FoundLink>? = null
        val rejected = CopyOnWriteArrayList<String>()
        val linksArrived = CountDownLatch(1)

        @JavascriptInterface
        fun postMessage(json: String) {
            when (val message = EmailHtml.parseMessage(json, token)) {
                is EmailHtml.Message.Links -> {
                    links = message.links
                    linksArrived.countDown()
                }
                is EmailHtml.Message.Click -> {}
                null -> rejected.add(json)
            }
        }
    }

    private fun load(html: String, allowJavaScript: Boolean): Pair<WebView, Probe> {
        val probe = Probe(token)
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                // Same setup as EmailBodyView.
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(probe, "TrustmailBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(EmailHtml.injectedScript(token), null)
                    }
                }
                loadDataWithBaseURL(null, EmailHtml.document(html, BodyType.Html, allowJavaScript), "text/html", "utf-8", null)
            }
        }
        assertTrue("links never arrived", probe.linksArrived.await(15, TimeUnit.SECONDS))
        return webView to probe
    }

    private fun textOf(webView: WebView, id: String): String? {
        val result = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("document.getElementById('$id').textContent") {
                result[0] = it
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result[0]?.trim('"')
    }

    private val html = """
        <p>Please <a href="http://login.example.net/verify">paypal.com/login</a></p>
        <p><a href="https://bit.ly/abc">Fix my account</a></p>
        <p><a href="javascript:alert(1)">Unsubscribe</a></p>
        <p id="js-status">blocked</p>
        <script>document.getElementById('js-status').textContent = 'running'</script>
    """.trimIndent()

    private val expectedLinks = listOf(
        FoundLink("http://login.example.net/verify", "paypal.com/login"),
        FoundLink("https://bit.ly/abc", "Fix my account"),
        FoundLink("javascript:alert(1)", "Unsubscribe"),
    )

    @Test
    fun scriptsInTheEmailAreBlockedByDefault() {
        val (webView, _) = load(html, allowJavaScript = false)
        assertEquals("blocked", textOf(webView, "js-status"))
    }

    @Test
    fun scriptsInTheEmailRunWhenAllowed() {
        val (webView, _) = load(html, allowJavaScript = true)
        assertEquals("running", textOf(webView, "js-status"))
    }

    /** iOS finds the same links whichever way the setting is, and so must Android. */
    @Test
    fun everyLinkIsFoundWithJavaScriptOff() {
        assertEquals(expectedLinks, load(html, allowJavaScript = false).second.links)
    }

    @Test
    fun everyLinkIsFoundWithJavaScriptOn() {
        assertEquals(expectedLinks, load(html, allowJavaScript = true).second.links)
    }

    /** With scripts allowed the page can reach the bridge too; it must not be able to fake a scan. */
    @Test
    fun aScriptInTheEmailCannotForgeScanMessages() {
        val forging = """
            $html
            <script>
              TrustmailBridge.postMessage(JSON.stringify({ type: 'links', links: [] }));
              TrustmailBridge.postMessage(JSON.stringify({ token: 'guess', type: 'links', links: [] }));
            </script>
        """.trimIndent()
        val (_, probe) = load(forging, allowJavaScript = true)
        assertEquals(expectedLinks, probe.links)
        assertEquals(2, probe.rejected.size)
    }
}
