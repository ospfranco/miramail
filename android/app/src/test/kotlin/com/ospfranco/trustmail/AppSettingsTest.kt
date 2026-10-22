package com.ospfranco.trustmail

import com.ospfranco.trustmail.settings.AppSettings
import com.ospfranco.trustmail.settings.MemorySettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun emailJavaScriptIsOffByDefault() {
        assertFalse(AppSettings(MemorySettingsStore()).allowEmailJavaScript)
    }

    @Test
    fun emailJavaScriptChoiceIsPersisted() {
        val store = MemorySettingsStore()
        val settings = AppSettings(store)
        settings.updateAllowEmailJavaScript(true)
        assertTrue(settings.allowEmailJavaScript)
        assertTrue(AppSettings(store).allowEmailJavaScript)
        settings.updateAllowEmailJavaScript(false)
        assertFalse(AppSettings(store).allowEmailJavaScript)
    }

    @Test
    fun footerLinksCountByDefault() {
        assertFalse(AppSettings(MemorySettingsStore()).ignoreFooterLinks)
    }

    @Test
    fun footerLinksChoiceIsPersisted() {
        val store = MemorySettingsStore()
        val settings = AppSettings(store)
        settings.updateIgnoreFooterLinks(true)
        assertTrue(settings.ignoreFooterLinks)
        assertTrue(AppSettings(store).ignoreFooterLinks)
        settings.updateIgnoreFooterLinks(false)
        assertFalse(AppSettings(store).ignoreFooterLinks)
    }

    @Test
    fun introIsShownUntilSeen() {
        val store = MemorySettingsStore()
        val settings = AppSettings(store)
        assertFalse(settings.hasSeenIntro)
        settings.updateHasSeenIntro(true)
        assertTrue(AppSettings(store).hasSeenIntro)
    }

    /** Pinned on both platforms: the addresses the stores and Google's consent screen list. */
    @Test
    fun privacyPolicyAndTermsLinkToTheWebsite() {
        assertEquals("https://miramail.me/privacy", AppSettings.PRIVACY_POLICY_URL)
        assertEquals("https://miramail.me/terms", AppSettings.TERMS_OF_USE_URL)
    }
}
