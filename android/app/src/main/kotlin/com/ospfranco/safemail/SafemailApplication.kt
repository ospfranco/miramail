package com.ospfranco.safemail

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import com.ospfranco.safemail.mail.ConsentLauncher
import com.ospfranco.safemail.mail.GmailClient
import com.ospfranco.safemail.mail.GoogleAuth
import com.ospfranco.safemail.mail.Services
import com.ospfranco.safemail.security.PublicSuffixList
import com.ospfranco.safemail.settings.AppSettings
import com.ospfranco.safemail.settings.SharedPreferencesStore

/** Forwards consent requests to whichever activity is currently on screen. */
object ConsentBridge : ConsentLauncher {
    @Volatile
    var delegate: ConsentLauncher? = null

    override suspend fun launch(intentSender: IntentSender): Intent? =
        (delegate ?: error("No activity to show the Google consent screen")).launch(intentSender)
}

class SafemailApplication : Application() {
    val liveServices: Services by lazy {
        val auth = GoogleAuth(this, ConsentBridge)
        Services(auth, GmailClient { forceRefresh -> auth.accessToken(forceRefresh) })
    }

    val settings: AppSettings by lazy {
        AppSettings(SharedPreferencesStore(getSharedPreferences("settings", MODE_PRIVATE)))
    }

    override fun onCreate() {
        super.onCreate()
        PublicSuffixList.shared = PublicSuffixList.load(this)
    }
}
