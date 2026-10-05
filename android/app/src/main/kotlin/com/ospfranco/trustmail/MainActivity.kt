package com.ospfranco.trustmail

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
import com.ospfranco.trustmail.mail.ConsentLauncher
import com.ospfranco.trustmail.mail.Demo
import com.ospfranco.trustmail.mail.OutgoingMessage
import com.ospfranco.trustmail.mail.Services
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ospfranco.trustmail.ui.ComposeScreen
import com.ospfranco.trustmail.ui.ComposeViewModel
import com.ospfranco.trustmail.ui.NoticeBanner
import com.ospfranco.trustmail.ui.Spacing
import com.ospfranco.trustmail.ui.InboxScreen
import com.ospfranco.trustmail.ui.IntroScreen
import com.ospfranco.trustmail.ui.MessageScreen
import com.ospfranco.trustmail.ui.MessageViewModel
import com.ospfranco.trustmail.ui.TrustmailTheme
import com.ospfranco.trustmail.settings.AppSettings
import com.ospfranco.trustmail.settings.MemorySettingsStore
import com.ospfranco.trustmail.ui.SessionViewModel
import com.ospfranco.trustmail.ui.SettingsScreen
import kotlinx.coroutines.CompletableDeferred

/** How long a screen takes to slide in or out. */
private const val StackAnimationMs = 300

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

    private fun services(): Services =
        if (isDemo) Demo.services(signedOut = intent.getBooleanExtra(Demo.EXTRA_SIGNED_OUT, false))
        else (application as TrustmailApplication).liveServices

    /** Demo runs start from the default settings every launch and never touch the real preferences. */
    private fun settings(): AppSettings =
        if (isDemo) AppSettings(MemorySettingsStore()) else (application as TrustmailApplication).settings

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ConsentBridge.delegate = this
        val services = services()
        val settings = settings()

        setContent {
            TrustmailTheme {
                val navController = rememberNavController()
                val session: SessionViewModel = viewModel(factory = viewModelFactory { initializer { SessionViewModel(services, noticeDurationMs = if (isDemo) 8000 else 2500) } })

                // The intro first (once, before anyone has signed in), then the inbox.
                val showIntro = session.user == null && !session.isLoading && !settings.hasSeenIntro
                // Someone who is already signed in has no need for the intro, now or after signing out.
                LaunchedEffect(session.user) { if (session.user != null) settings.updateHasSeenIntro(true) }

                if (showIntro) {
                    IntroScreen(onContinue = { settings.updateHasSeenIntro(true) })
                    return@TrustmailTheme
                }

                Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = "inbox",
                    // A normal stack: a pushed screen slides in from the right while the one underneath shifts a
                    // little to the left; going back (button or swipe) plays the same thing in reverse.
                    enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(StackAnimationMs)) },
                    exitTransition = {
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(StackAnimationMs), targetOffset = { it / 4 })
                    },
                    popEnterTransition = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(StackAnimationMs), initialOffset = { it / 4 })
                    },
                    popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(StackAnimationMs)) },
                    // The swipe-back gesture has transitions of its own, which default to a shrinking screen.
                    // Use the same slide, so the screen follows the finger like a normal stack pop.
                    predictivePopEnterTransition = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(StackAnimationMs), initialOffset = { it / 4 })
                    },
                    predictivePopExitTransition = {
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(StackAnimationMs))
                    },
                ) {
                    composable("inbox") {
                        InboxScreen(
                            session,
                            onOpenMessage = { id -> navController.navigate("message/$id") },
                            onOpenSettings = { navController.navigate("settings") },
                            onNewMessage = { navController.navigate("compose") },
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
                        MessageScreen(
                            model,
                            settings,
                            session,
                            onBack = { navController.popBackStack() },
                            onReply = { navController.navigate("compose/$id") },
                        )
                    }
                    composable("compose") {
                        val compose: ComposeViewModel = viewModel(
                            key = "compose-new",
                            factory = viewModelFactory { initializer { ComposeViewModel(OutgoingMessage("", "", ""), services) } },
                        )
                        ComposeScreen(compose, session, onBack = { navController.popBackStack() })
                    }
                    composable("compose/{id}") { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val message = remember(entry) { navController.getBackStackEntry("message/$id") }
                        val original: MessageViewModel = viewModel(
                            viewModelStoreOwner = message,
                            key = "message-$id",
                            factory = viewModelFactory { initializer { MessageViewModel(id, services) } },
                        )
                        val draft = original.reply
                        if (draft != null) {
                            val compose: ComposeViewModel = viewModel(
                                key = "compose-$id",
                                factory = viewModelFactory { initializer { ComposeViewModel(draft, services) } },
                            )
                            ComposeScreen(compose, session, onBack = { navController.popBackStack() })
                        }
                    }
                }
                session.notice?.let { notice ->
                    NoticeBanner(
                        notice,
                        Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = Spacing.two),
                    )
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
