package com.ospfranco.safemail

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ospfranco.safemail.mail.Demo
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Drives the app in demo mode (see `Demo` in the app sources), so no Google account is needed.
 * The security checks hit the real network, hence the generous timeouts.
 */
@RunWith(AndroidJUnit4::class)
class SafemailUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private fun launch() {
        val context = instrumentation.targetContext
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra(Demo.EXTRA, true))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "android-$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun inboxListsDemoMessagesAndSignsOut() {
        launch()
        waitForTag("message-demo-1", 10_000)
        compose.onNodeWithText("demo@example.com").assertIsDisplayed()
        compose.onNodeWithTag("message-demo-2").assertIsDisplayed()
        screenshot("inbox")

        compose.onNodeWithTag("sign-out").performClick()
        waitForTag("sign-in", 5_000)
        compose.onNodeWithTag("sign-in").performClick()
        waitForTag("message-demo-1", 5_000)
    }

    @Test
    fun phishingEmailIsFlaggedAndRiskyLinksAskForConfirmation() {
        launch()
        waitForTag("message-demo-2", 10_000)
        compose.onNodeWithTag("message-demo-2").performClick()

        // The panel tab only appears once every link and the sender have been checked.
        waitForTag("security-tab")
        compose.onNodeWithTag("security-tab").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Security: This email looks dangerous")),
        )
        screenshot("message-scanned")

        // The email's own <script> must not have run.
        assertNotNull(device.wait(Until.findObject(By.text("Scripts in this email are blocked.")), 10_000))

        compose.onNodeWithTag("security-tab").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("security-close").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This email looks dangerous").assertIsDisplayed()
        compose.onNodeWithText("Sender verification failed").assertIsDisplayed()
        compose.onNodeWithText("First message from this sender").assertIsDisplayed()
        // Three links, including the javascript: one, with JavaScript in emails off (the default).
        compose.onNodeWithText("3 links scanned").assertIsDisplayed()
        screenshot("security-panel")
        compose.onNodeWithTag("security-close").performClick()
        compose.waitForIdle()

        // Tap the link inside the email (the WebView exposes it to accessibility as a node).
        val link = device.wait(Until.findObject(By.text("paypal.com/login")), 10_000)
        assertNotNull("link not found in the email", link)
        link.click()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagOrText("This link looks unsafe") }
        // Scoped to the dialog: the same sentence also sits (off screen) in the closed security panel.
        compose.onNode(
            hasText("Text shows paypal.com but the link goes to login.paypa1-secure.example.net.", substring = true) and hasAnyAncestor(isDialog()),
        ).assertIsDisplayed()
        screenshot("link-warning")
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagOrText(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun plainTextEmailFromKnownSender() {
        launch()
        waitForTag("message-demo-1", 10_000)
        compose.onNodeWithTag("message-demo-1").performClick()
        waitForTag("security-tab")
        compose.onNodeWithTag("security-tab").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("security-close").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sender verified").assertIsDisplayed()
        compose.onNodeWithText("Known sender").assertIsDisplayed()
        screenshot("plain-text-panel")
    }

    private fun openSettings() {
        waitForTag("settings-button", 10_000)
        compose.onNodeWithTag("settings-button").performClick()
        waitForTag("javascript-toggle", 5_000)
    }

    @Test
    fun settingsButtonOpensSettingsWithJavaScriptOffByDefault() {
        launch()
        openSettings()
        compose.onNodeWithTag("javascript-toggle").assertIsOff()
        compose.onNodeWithText("Allow JavaScript in emails").assertIsDisplayed()
        compose.onNodeWithTag("settings-sign-out").assertIsDisplayed()
        screenshot("settings")
    }

    @Test
    fun enablingJavaScriptLetsTheEmailsOwnScriptsRunAndKeepsEveryLinkScanned() {
        launch()
        openSettings()
        compose.onNodeWithTag("javascript-toggle").performClick()
        compose.onNodeWithTag("javascript-toggle").assertIsOn()
        device.pressBack()

        waitForTag("message-demo-2", 10_000)
        compose.onNodeWithTag("message-demo-2").performClick()
        waitForTag("security-tab")
        assertNotNull(device.wait(Until.findObject(By.text("Scripts in this email are running.")), 10_000))
        screenshot("message-javascript-on")

        compose.onNodeWithTag("security-tab").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("security-close").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("3 links scanned").assertIsDisplayed()
        compose.onNodeWithText("This email looks dangerous").assertIsDisplayed()
    }

    @Test
    fun settingsHasSignOutThatReturnsToTheSignInScreen() {
        launch()
        openSettings()
        compose.onNodeWithText("demo@example.com").assertIsDisplayed()
        compose.onNodeWithTag("settings-sign-out").performClick()
        // Back on the inbox, now signed out.
        waitForTag("sign-in", 5_000)
        assert(compose.onAllNodesWithTag("sign-out").fetchSemanticsNodes().isEmpty())

        // Signed out, the settings screen still opens but offers no sign out.
        compose.onNodeWithTag("settings-button").performClick()
        waitForTag("javascript-toggle", 5_000)
        assert(compose.onAllNodesWithTag("settings-sign-out").fetchSemanticsNodes().isEmpty())
        screenshot("settings-signed-out")
    }
}
