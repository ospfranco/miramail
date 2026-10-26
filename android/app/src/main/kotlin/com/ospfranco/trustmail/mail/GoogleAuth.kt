package com.ospfranco.trustmail.mail

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.IOException

/** Shows Google's consent UI. Implemented by the activity, which owns the result launcher. */
interface ConsentLauncher {
    /** Returns the result intent, or null if the user cancelled. */
    suspend fun launch(intentSender: IntentSender): Intent?
}

/**
 * Google authorization for Gmail. Android has no refresh tokens in this flow: once the user has
 * granted the scopes, `authorize()` hands out fresh access tokens without any UI.
 * The Android OAuth client is matched by package name + signing SHA-1, so no client ID appears here: Google Cloud has
 * clients for me.miramail with the debug key (sideload APK) and Google Play's signing key. See RELEASING.md.
 */
class GoogleAuth(
    context: Context,
    private val consent: ConsentLauncher,
) : AuthService {
    companion object {
        /** Full Gmail access, the scope registered in Google Cloud. */
        val GMAIL_SCOPES = listOf(
            "https://mail.google.com/",
        )
        private const val PREFS = "google_auth"
        private const val KEY_EMAIL = "email"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = Identity.getAuthorizationClient(appContext)
    private val scopes = GMAIL_SCOPES.map(::Scope)
    private val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()

    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var pendingToken: CompletableDeferred<String>? = null

    /** Returns the grant without UI, or null if the user still has to consent. */
    private suspend fun authorizeSilently(): AuthorizationResult? {
        val result = client.authorize(request).await()
        return if (result.hasResolution()) null else result
    }

    override suspend fun restoreSession(): UserProfile? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val result = try {
            authorizeSilently()
        } catch (e: ApiException) {
            // Offline: stay signed in (and keep the saved inbox). The token is fetched on the next request that
            // reaches Google. Any other failure means the grant is gone.
            if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) return UserProfile(email)
            null
        } catch (e: IOException) {
            return UserProfile(email)
        }
        if (result == null) {
            prefs.edit().remove(KEY_EMAIL).apply()
            return null
        }
        cachedToken = result.accessToken
        return UserProfile(email)
    }

    override suspend fun signIn(): UserProfile? {
        var result = client.authorize(request).await()
        if (result.hasResolution()) {
            val data = consent.launch(result.pendingIntent!!.intentSender) ?: return null
            result = try {
                client.getAuthorizationResultFromIntent(data)
            } catch (e: ApiException) {
                if (e.statusCode == CommonStatusCodes.CANCELED || e.statusCode == 16) return null
                throw e
            }
        }
        val token = result.accessToken ?: return null
        cachedToken = token
        val email = GmailClient(accessToken = { _ -> token }).profileEmail()
        prefs.edit().putString(KEY_EMAIL, email).apply()
        return UserProfile(email)
    }

    override suspend fun signOut() {
        val email = prefs.getString(KEY_EMAIL, null)
        val token = cachedToken
        prefs.edit().remove(KEY_EMAIL).apply()
        cachedToken = null
        // Revoking forgets the grant, so the next sign-in shows the account chooser again.
        try {
            token?.let { client.clearToken(ClearTokenRequest.builder().setToken(it).build()).await() }
            if (email != null) {
                client.revokeAccess(
                    RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(scopes).build(),
                ).await()
            }
        } catch (e: Exception) {
            // Best effort: the local session is already gone.
        }
    }

    /**
     * Returns a valid access token. Pass [forceRefresh] after a 401 to discard the cached one.
     * Concurrent callers share a single pending request, since the inbox fires many at once.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val (deferred, owner) = mutex.withLock {
            if (!forceRefresh) cachedToken?.let { return it }
            pendingToken?.let { return@withLock it to false }
            CompletableDeferred<String>().also { pendingToken = it } to true
        }
        if (owner) {
            try {
                if (forceRefresh) cachedToken?.let { client.clearToken(ClearTokenRequest.builder().setToken(it).build()).await() }
                val result = authorizeSilently() ?: throw GmailException(401, "Sign in again to continue.")
                val token = result.accessToken ?: throw GmailException(401, "No access token")
                cachedToken = token
                deferred.complete(token)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                mutex.withLock { pendingToken = null }
            }
        }
        return deferred.await()
    }
}
