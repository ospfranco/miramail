package com.ospfranco.trustmail

import com.ospfranco.trustmail.settings.AppSettings
import com.ospfranco.trustmail.settings.MemorySettingsStore
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
    fun introIsShownUntilSeen() {
        val store = MemorySettingsStore()
        val settings = AppSettings(store)
        assertFalse(settings.hasSeenIntro)
        settings.updateHasSeenIntro(true)
        assertTrue(AppSettings(store).hasSeenIntro)
    }
}
