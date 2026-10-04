package com.ospfranco.safemail.settings

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
    private companion object {
        const val ALLOW_EMAIL_JAVASCRIPT = "allowEmailJavaScript"
        const val HAS_SEEN_INTRO = "hasSeenIntro"
    }

    /**
     * Whether scripts inside an email may run. Off by default: a script can change what the reader
     * sees after the links were scanned. Safemail's own scanning script runs either way.
     */
    var allowEmailJavaScript by mutableStateOf(store.getBoolean(ALLOW_EMAIL_JAVASCRIPT, false))
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
}
