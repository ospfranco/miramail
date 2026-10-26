package com.ospfranco.trustmail.settings

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where settings are kept. The app uses SharedPreferences; tests use memory. */
interface SettingsStore {
    fun getBoolean(key: String, default: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)
}

class SharedPreferencesStore(private val prefs: SharedPreferences) : SettingsStore {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}

class MemorySettingsStore : SettingsStore {
    private val values = HashMap<String, Boolean>()

    override fun getBoolean(key: String, default: Boolean) = values[key] ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }
}

/** User preferences, persisted on the device. */
class AppSettings(private val store: SettingsStore) {
    companion object {
        /**
         * The privacy policy and terms of use on the website, linked from the settings (both stores require an
         * in-app link). The same addresses are entered in App Store Connect, the Play Console and Google's OAuth
         * consent screen.
         */
        const val PRIVACY_POLICY_URL = "https://miramail.me/privacy"
        const val TERMS_OF_USE_URL = "https://miramail.me/terms"

        private const val ALLOW_EMAIL_JAVASCRIPT = "allowEmailJavaScript"
        private const val HAS_SEEN_INTRO = "hasSeenIntro"
        private const val IGNORE_FOOTER_LINKS = "ignoreFooterLinks"
    }

    /**
     * Whether scripts inside an email may run. Off by default: a script can change what the reader
     * sees after the links were scanned. Miramail's own scanning script runs either way.
     */
    var allowEmailJavaScript by mutableStateOf(store.getBoolean(ALLOW_EMAIL_JAVASCRIPT, false))
        private set

    /**
     * Whether suspicious links in an email's footer (unsubscribe, privacy policy, social media) are left
     * out of the email's verdict. Off by default. Footer links are still scanned and badged, and an unsafe
     * one still counts, so wrapping a phishing link in a footer can't hide it.
     */
    var ignoreFooterLinks by mutableStateOf(store.getBoolean(IGNORE_FOOTER_LINKS, false))
        private set

    /** Whether the intro screen has been shown (or the user signed in without it), so it only appears once. */
    var hasSeenIntro by mutableStateOf(store.getBoolean(HAS_SEEN_INTRO, false))
        private set

    fun updateHasSeenIntro(value: Boolean) {
        hasSeenIntro = value
        store.putBoolean(HAS_SEEN_INTRO, value)
    }

    fun updateAllowEmailJavaScript(value: Boolean) {
        allowEmailJavaScript = value
        store.putBoolean(ALLOW_EMAIL_JAVASCRIPT, value)
    }

    fun updateIgnoreFooterLinks(value: Boolean) {
        ignoreFooterLinks = value
        store.putBoolean(IGNORE_FOOTER_LINKS, value)
    }
}
