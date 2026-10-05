package com.ospfranco.trustmail

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import com.ospfranco.trustmail.mail.ConsentLauncher
import com.ospfranco.trustmail.mail.GmailClient
import com.ospfranco.trustmail.mail.GoogleAuth
import com.ospfranco.trustmail.mail.Services
import com.ospfranco.trustmail.security.KnownDomains
import com.ospfranco.trustmail.security.PublicSuffixList
import com.ospfranco.trustmail.settings.AppSettings
import com.ospfranco.trustmail.settings.SharedPreferencesStore

/** Forwards consent requests to whichever activity is currently on screen. */
object ConsentBridge : ConsentLauncher {
    @Volatile
    var delegate: ConsentLauncher? = null

    override suspend fun launch(intentSender: IntentSender): Intent? =
        (delegate ?: error("No activity to show the Google consent screen")).launch(intentSender)
}

class TrustmailApplication : Application() {
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
        KnownDomains.shared = KnownDomains.load(this)
    }
}
