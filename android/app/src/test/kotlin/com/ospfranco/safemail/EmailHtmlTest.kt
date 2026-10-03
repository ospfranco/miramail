package com.ospfranco.safemail

import com.ospfranco.safemail.mail.BodyType
import com.ospfranco.safemail.security.FoundLink
import com.ospfranco.safemail.security.ScannedLink
import com.ospfranco.safemail.security.Verdict
import com.ospfranco.safemail.ui.EmailHtml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailHtmlTest {
    @Test
    fun escapesHtml() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;", EmailHtml.escape("<a href=\"x\">&</a>"))
    }

    @Test
    fun linkifiesPlainText() {
        val html = EmailHtml.textToHtml("See https://example.com/a?b=1, or http://x.org. <script>")
        assertTrue(html.contains("<a href=\"https://example.com/a?b=1\">https://example.com/a?b=1</a>,"))
        assertTrue(html.contains("<a href=\"http://x.org\">http://x.org</a>."))
        assertTrue(html.contains("&lt;script&gt;"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun documentBlocksScriptsAndKeepsBodies() {
        val htmlDoc = EmailHtml.document("<p>Hello <a href=\"https://a.com\">a</a></p>", BodyType.Html, allowScripts = false)
        assertTrue(htmlDoc.contains("Content-Security-Policy"))
        assertTrue(htmlDoc.contains("default-src 'none'"))
        assertTrue(htmlDoc.contains("<body><p>Hello <a href=\"https://a.com\">a</a></p></body>"))
        assertTrue(htmlDoc.contains(".sm-danger { background: #E5484D; }"))

        val textDoc = EmailHtml.document("a < b", BodyType.Text, allowScripts = false)
        assertTrue(textDoc.contains("a &lt; b"))
        assertTrue(textDoc.contains("white-space: pre-wrap"))
    }

    @Test
    fun contentSecurityPolicyAllowsScriptsOnlyWhenEnabled() {
        assertFalse(EmailHtml.csp(allowScripts = false).contains("script-src"))
        assertTrue(EmailHtml.csp(allowScripts = false).contains("default-src 'none'"))
        assertTrue(EmailHtml.csp(allowScripts = true).contains("script-src 'unsafe-inline' https:"))
        assertTrue(EmailHtml.csp(allowScripts = true).contains("default-src 'none'"))
        assertTrue(EmailHtml.document("x", BodyType.Html, allowScripts = true).contains("script-src"))
        assertFalse(EmailHtml.document("x", BodyType.Html, allowScripts = false).contains("script-src"))
    }

    @Test
    fun badgeScriptCarriesVerdictsByUrl() {
        val links = listOf(
            ScannedLink("https://a.com", "", "a.com", Verdict.Safe, emptyList()),
            ScannedLink("http://b.com/?q=\"x\"", "", "b.com", Verdict.Danger, emptyList()),
        )
        val script = EmailHtml.applyBadgesScript(links)
        assertTrue(script.startsWith("window.__safemailApply("))
        val parsed = Json.parseToJsonElement(script.removePrefix("window.__safemailApply(").removeSuffix(");")).jsonObject
        assertEquals("safe", parsed["https://a.com"]!!.jsonObject["verdict"]!!.jsonPrimitive.content)
        assertEquals("✓", parsed["https://a.com"]!!.jsonObject["glyph"]!!.jsonPrimitive.content)
        assertEquals("danger", parsed["http://b.com/?q=\"x\""]!!.jsonObject["verdict"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesMessagesFromThePage() {
        assertEquals(
            EmailHtml.Message.Links(listOf(FoundLink("https://a.com", "A"), FoundLink("mailto:x@y.z", ""))),
            EmailHtml.parseMessage("""{"token":"t0k","type":"links","links":[{"url":"https://a.com","text":"A"},{"url":"mailto:x@y.z","text":""}]}""", "t0k"),
        )
        assertEquals(EmailHtml.Message.Click("https://a.com"), EmailHtml.parseMessage("""{"token":"t0k","type":"click","url":"https://a.com"}""", "t0k"))
        assertNull(EmailHtml.parseMessage("""{"token":"t0k","type":"other"}""", "t0k"))
        assertNull(EmailHtml.parseMessage("not json", "t0k"))
    }

    /** With scripts allowed the page can reach the bridge too, so messages without the right token are dropped. */
    @Test
    fun ignoresMessagesWithoutTheRightToken() {
        val forged = """{"type":"links","links":[]}"""
        assertNull(EmailHtml.parseMessage(forged, "t0k"))
        assertNull(EmailHtml.parseMessage("""{"token":"guess","type":"click","url":"https://a.com"}""", "t0k"))
        assertTrue(EmailHtml.injectedScript("t0k").contains("message.token = 't0k'"))
    }
}
