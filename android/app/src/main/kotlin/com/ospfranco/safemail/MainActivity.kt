package com.ospfranco.safemail

import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ospfranco.safemail.mail.ConsentLauncher
import com.ospfranco.safemail.mail.Demo
import com.ospfranco.safemail.mail.Services
import com.ospfranco.safemail.ui.InboxScreen
import com.ospfranco.safemail.ui.MessageScreen
import com.ospfranco.safemail.ui.MessageViewModel
import com.ospfranco.safemail.ui.SafemailTheme
import com.ospfranco.safemail.settings.AppSettings
import com.ospfranco.safemail.settings.MemorySettingsStore
import com.ospfranco.safemail.ui.SessionViewModel
import com.ospfranco.safemail.ui.SettingsScreen
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity(), ConsentLauncher {
    private var pendingConsent: CompletableDeferred<Intent?>? = null

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingConsent?.complete(if (result.resultCode == RESULT_OK) result.data else null)
        pendingConsent = null
    }

    override suspend fun launch(intentSender: IntentSender): Intent? {
        val result = CompletableDeferred<Intent?>()
        pendingConsent = result
        consentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        return result.await()
    }

    private val isDemo get() = BuildConfig.DEBUG && intent.getBooleanExtra(Demo.EXTRA, false)

    private fun services(): Services = if (isDemo) Demo.services else (application as SafemailApplication).liveServices

    /** Demo runs start from the default settings every launch and never touch the real preferences. */
    private fun settings(): AppSettings =
        if (isDemo) AppSettings(MemorySettingsStore()) else (application as SafemailApplication).settings

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ConsentBridge.delegate = this
        val services = services()
        val settings = settings()

        setContent {
            SafemailTheme {
                val navController = rememberNavController()
                val session: SessionViewModel = viewModel(factory = viewModelFactory { initializer { SessionViewModel(services) } })

                NavHost(navController = navController, startDestination = "inbox") {
                    composable("inbox") {
                        InboxScreen(
                            session,
                            onOpenMessage = { id -> navController.navigate("message/$id") },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(settings, session, onBack = { navController.popBackStack() })
                    }
                    composable("message/{id}") { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val model: MessageViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            key = "message-$id",
                            factory = viewModelFactory { initializer { MessageViewModel(id, services) } },
                        )
                        MessageScreen(model, settings, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (ConsentBridge.delegate === this) ConsentBridge.delegate = null
        super.onDestroy()
    }
}
