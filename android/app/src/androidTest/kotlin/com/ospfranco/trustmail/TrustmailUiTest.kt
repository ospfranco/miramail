package com.ospfranco.trustmail

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import com.ospfranco.trustmail.mail.Demo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
class TrustmailUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private fun launch(signedOut: Boolean = false, offline: Boolean = false) {
        val context = instrumentation.targetContext
        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .putExtra(Demo.EXTRA, true)
                .putExtra(Demo.EXTRA_SIGNED_OUT, signedOut)
                .putExtra(Demo.EXTRA_OFFLINE, offline),
        )
    }

    // Saved inbox

    @Test
    fun offlineLaunchShowsTheSavedInbox() {
        launch(offline = true)
        waitForTag("saved-inbox", 10_000)
        compose.onNodeWithTag("message-demo-1").assertExists()
        // The saved copy keeps its markers.
        compose.onNode(hasTestTag("first-message") and hasAnyAncestor(hasTestTag("message-demo-2")), useUnmergedTree = true).assertExists()
        screenshot("inbox-saved")
    }

    @Test
    fun onlineLaunchShowsNoSavedInboxNote() {
        launch()
        waitForTag("message-demo-1", 10_000)
        compose.onNodeWithTag("saved-inbox").assertDoesNotExist()
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

    /** Scrolls the email body (a WebView, outside Compose) a little, with a finger swipe near the bottom of the screen. */
    private fun scrollEmailBody() {
        val x = device.displayWidth / 2
        device.swipe(x, device.displayHeight * 85 / 100, x, device.displayHeight * 70 / 100, 20)
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun inboxListsDemoMessagesWithAPencilAndSettingsButNoSignOut() {
        launch()
        waitForTag("message-demo-1", 10_000)
        compose.onNodeWithText("demo@example.com").assertIsDisplayed()
        compose.onNodeWithTag("message-demo-2").assertIsDisplayed()
        compose.onNodeWithTag("new-message").assertIsDisplayed()
        compose.onNodeWithTag("settings-button").assertIsDisplayed()
        assert(compose.onAllNodesWithTag("sign-out").fetchSemanticsNodes().isEmpty()) { "signing out lives in the settings now" }
        screenshot("inbox")

        // Signing out and back in, via the settings.
        compose.onNodeWithTag("settings-button").performClick()
        waitForTag("settings-sign-out", 5_000)
        compose.onNodeWithTag("settings-sign-out").performClick()
        waitForTag("sign-in", 5_000)
        compose.onNodeWithTag("sign-in").performClick()
        waitForTag("message-demo-1", 5_000)
    }

    @Test
    fun phishingEmailIsFlaggedAndRiskyLinksAskForConfirmation() {
        launch()
        waitForTag("message-demo-2", 10_000)
        // In the inbox: the forged sender's domain is marked, and so is a sender you've never written with.
        fun inRow(id: String, tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag("message-$id"))
        compose.waitUntil(10_000) { compose.onAllNodes(inRow("demo-2", "first-message"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(inRow("demo-2", "sender-warning"), useUnmergedTree = true).assertExists()
        compose.onNode(inRow("demo-1", "sender-warning"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(inRow("demo-1", "first-message"), useUnmergedTree = true).assertDoesNotExist()
        screenshot("inbox-warnings")

        compose.onNodeWithTag("message-demo-2").performClick()

        // Once every link and the sender have been checked, the problems show in the header, unsafe ones first.
        waitForTag("security-findings")
        assertEquals(
            listOf("Sender verification failed", "Unsafe links", "First message from this sender", "Suspicious links"),
            findingTitles(),
        )
        // Each one opens to explain itself in plain words.
        compose.onNodeWithText("First message from this sender").performClick()
        compose.onNodeWithText("This is the first email you get from this address.", substring = true).assertIsDisplayed()
        // The header marks the sender's domain like an unsafe link.
        compose.onNodeWithTag("sender-warning").assertExists()
        screenshot("message-scanned")

        // The email's own <script> must not have run. (The warnings fill the top of the screen, so scroll to it.)
        scrollEmailBody()
        assertNotNull(device.wait(Until.findObject(By.text("Scripts in this email are blocked.")), 10_000))

        // Tap the link inside the email (the WebView exposes it to accessibility as a node).
        val link = device.wait(Until.findObject(By.text("paypal.com/login")), 10_000)
        assertNotNull("link not found in the email", link)
        link.click()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagOrText("This link looks unsafe") }
        compose.onNode(
            hasText("Shown as: paypal.com/login\nGoes to: http://login.paypa1-secure.example.net/verify\n\n", substring = true) and hasAnyAncestor(isDialog()),
        ).assertIsDisplayed()
        compose.onNode(
            // Why, in plain words.
            hasText("The link's text shows one website, but it takes you to another.", substring = true) and hasAnyAncestor(isDialog()),
        ).assertIsDisplayed()
        screenshot("link-warning")
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagOrText(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The titles of the problems the header can show. */
    private val titles = setOf(
        "Sender not verified", "Sender verification failed", "First message from this sender", "Sender history unknown",
        "Unsafe links", "Suspicious links",
    )

    /** The problem titles in the header, top to bottom. */
    private fun findingTitles(): List<String> =
        compose.onAllNodes(hasAnyAncestor(hasTestTag("security-findings")), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { text -> text.text } }
            .filter { it in titles }

    @Test
    fun theHeaderShrinksWhileReadingALongEmail() {
        launch()
        waitForTag("message-demo-1", 10_000)
        compose.onNodeWithTag("message-demo-1").performClick()
        waitForTag("message-header", 10_000)
        // Wait for the check to finish: the header changes height then, and a swipe before the page is ready is lost.
        compose.waitUntil(60_000) {
            listOf("security-safe", "security-findings").any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        }
        // Scroll the email body (a WebView, outside Compose) with a finger swipe in the middle of the screen.
        val x = device.displayWidth / 2
        device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 20)
        waitForTag("message-header-compact", 5_000)
        compose.onNodeWithText("Hello from the Trustmail team").assertExists()
        screenshot("message-header-compact")
        repeat(2) { device.swipe(x, device.displayHeight / 3, x, device.displayHeight * 3 / 4, 20) }
        waitForTag("message-header", 5_000)
    }

    @Test
    fun plainTextEmailFromKnownSender() {
        launch()
        waitForTag("message-demo-1", 10_000)
        compose.onNodeWithTag("message-demo-1").performClick()
        // A verified, known sender: nothing about the sender in the header. (Its one link is checked over the real
        // network, so whether the header is green or warns about the link depends on the network.)
        compose.waitUntil(60_000) {
            listOf("security-safe", "security-findings").any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(findingTitles().none { it.startsWith("Sender") || it.startsWith("First") })
        compose.onNodeWithTag("sender-warning").assertDoesNotExist()
        screenshot("plain-text-scanned")
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
        compose.onNodeWithTag("footer-links-toggle").assertIsOff()
        compose.onNodeWithText("Ignore footer links").assertIsDisplayed()
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
        waitForTag("security-findings")
        scrollEmailBody()
        assertNotNull(device.wait(Until.findObject(By.text("Scripts in this email are running.")), 10_000))
        // The links are judged the same with the email's scripts on.
        assertTrue("Unsafe links" in findingTitles())
        screenshot("message-javascript-on")
    }

    @Test
    fun settingsHasSignOutThatReturnsToTheSignInScreen() {
        launch()
        openSettings()
        compose.onNodeWithText("demo@example.com").assertIsDisplayed()
        compose.onNodeWithTag("settings-sign-out").performClick()
        // Back on the inbox, now signed out.
        waitForTag("sign-in", 5_000)
        assert(compose.onAllNodesWithTag("new-message").fetchSemanticsNodes().isEmpty()) { "nothing can be sent while signed out" }

        // Signed out, the settings screen still opens but offers no sign out.
        compose.onNodeWithTag("settings-button").performClick()
        waitForTag("javascript-toggle", 5_000)
        assert(compose.onAllNodesWithTag("settings-sign-out").fetchSemanticsNodes().isEmpty())
        screenshot("settings-signed-out")
    }

    // Floating action bar and compose

    private fun openMessage(id: String) {
        waitForTag("message-$id", 10_000)
        compose.onNodeWithTag("message-$id").performClick()
        waitForTag("action-archive", 10_000)
    }

    private fun assertBackOnInbox(without: String, notice: String) {
        waitForTag("notice", 5_000)
        compose.onNodeWithTag("notice").assertTextEquals(notice)
        waitForTag("new-message", 5_000)
        assert(compose.onAllNodesWithTag("message-$without").fetchSemanticsNodes().isEmpty()) { "$without is still in the inbox" }
    }

    @Test
    fun theEmailHasAFloatingBarWithFourButtons() {
        launch()
        openMessage("demo-2")
        for (tag in listOf("action-delete", "action-archive", "action-move", "action-reply")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        waitForTag("security-findings")
    }

    @Test
    fun archiveTakesTheEmailOutOfTheInbox() {
        launch()
        openMessage("demo-1")
        compose.onNodeWithTag("action-archive").performClick()
        assertBackOnInbox(without = "demo-1", notice = "Archived")
        compose.onNodeWithTag("message-demo-2").assertIsDisplayed()
    }

    @Test
    fun deleteMovesTheEmailToTrash() {
        launch()
        openMessage("demo-2")
        compose.onNodeWithTag("action-delete").performClick()
        assertBackOnInbox(without = "demo-2", notice = "Moved to Trash")
        compose.onNodeWithTag("message-demo-1").assertIsDisplayed()
    }

    @Test
    fun moveOffersTheLabelsAndSpam() {
        launch()
        openMessage("demo-1")
        compose.onNodeWithTag("action-move").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Work").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Receipts").assertIsDisplayed()
        compose.onNodeWithText("Spam").assertIsDisplayed()
        screenshot("move-chooser")
        compose.onNodeWithText("Work").performClick()
        assertBackOnInbox(without = "demo-1", notice = "Moved to Work")
    }

    @Test
    fun cancellingMoveLeavesTheEmailWhereItIs() {
        launch()
        openMessage("demo-1")
        compose.onNodeWithTag("action-move").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Work").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("action-move").assertIsDisplayed()
        assert(compose.onAllNodesWithTag("notice").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun replyOpensAPrefilledComposeScreenAndSends() {
        launch()
        openMessage("demo-1")
        compose.onNodeWithTag("action-reply").performClick()
        waitForTag("compose-to", 5_000)
        compose.onNodeWithText("Reply").assertIsDisplayed()
        compose.onNodeWithTag("compose-to").assertTextContains("hello@example.com")
        compose.onNodeWithTag("compose-subject").assertTextContains("Re: Hello from the Trustmail team")

        compose.onNodeWithTag("compose-body").performTextInput("Thanks for the welcome!")
        screenshot("compose")
        compose.onNodeWithTag("compose-send").assertIsEnabled()
        compose.onNodeWithTag("compose-send").performClick()

        // Back on the email, with a confirmation.
        waitForTag("action-reply", 5_000)
        waitForTag("notice", 5_000)
        compose.onNodeWithTag("notice").assertTextEquals("Message sent")
    }

    @Test
    fun sendNeedsAValidRecipient() {
        launch()
        openMessage("demo-1")
        compose.onNodeWithTag("action-reply").performClick()
        waitForTag("compose-to", 5_000)
        compose.onNodeWithTag("compose-send").assertIsEnabled()

        compose.onNodeWithTag("compose-to").performTextClearance()
        compose.onNodeWithTag("compose-send").assertIsNotEnabled()

        compose.onNodeWithTag("compose-to").performTextInput("someone@example.org")
        compose.onNodeWithTag("compose-send").assertIsEnabled()
    }

    // Intro

    @Test
    fun firstLaunchShowsTheIntroBeforeSignIn() {
        launch(signedOut = true)
        waitForTag("intro-continue", 10_000)
        compose.onNodeWithText("Welcome to Trustmail").assertIsDisplayed()
        compose.onNodeWithTag("intro-preview").assertIsDisplayed()
        compose.onNodeWithText("Open an email").assertIsDisplayed()
        compose.onNodeWithText("See the verdict").assertIsDisplayed()
        compose.onNodeWithText("Tap with confidence").assertIsDisplayed()
        compose.onNodeWithTag("intro-google-note").assertTextContains("sign in with your Google account", substring = true)
        // Not signed in yet: no sign-in button until the user continues.
        assert(compose.onAllNodesWithTag("sign-in").fetchSemanticsNodes().isEmpty())
        screenshot("intro")

        compose.onNodeWithTag("intro-continue").performClick()
        waitForTag("sign-in", 5_000)
        assert(compose.onAllNodesWithText("Welcome to Trustmail").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("sign-in").performClick()
        waitForTag("message-demo-1", 5_000)
    }

    @Test
    fun signedInUsersNeverSeeTheIntro() {
        launch()
        waitForTag("message-demo-1", 10_000)
        assert(compose.onAllNodesWithText("Welcome to Trustmail").fetchSemanticsNodes().isEmpty())
        // ...even after signing out.
        compose.onNodeWithTag("settings-button").performClick()
        waitForTag("settings-sign-out", 5_000)
        compose.onNodeWithTag("settings-sign-out").performClick()
        waitForTag("sign-in", 5_000)
        assert(compose.onAllNodesWithText("Welcome to Trustmail").fetchSemanticsNodes().isEmpty())
    }

    // The pencil: a new email

    @Test
    fun thePencilOpensAnEmptyComposeScreenAndSends() {
        launch()
        waitForTag("new-message", 10_000)
        compose.onNodeWithTag("new-message").performClick()
        waitForTag("compose-to", 5_000)
        compose.onNodeWithText("New message").assertIsDisplayed()
        compose.onNodeWithTag("compose-send").assertIsNotEnabled() // no recipient yet

        compose.onNodeWithTag("compose-to").performTextInput("friend@example.org")
        compose.onNodeWithTag("compose-subject").performTextInput("Lunch?")
        compose.onNodeWithTag("compose-body").performTextInput("Are you free on Friday?")
        screenshot("compose-new")
        compose.onNodeWithTag("compose-send").assertIsEnabled()
        compose.onNodeWithTag("compose-send").performClick()

        // Back on the inbox, with a confirmation.
        waitForTag("notice", 5_000)
        compose.onNodeWithTag("notice").assertTextEquals("Message sent")
        compose.onNodeWithTag("new-message").assertIsDisplayed()
    }

    @Test
    fun signedOutThereIsNoPencil() {
        launch(signedOut = true)
        waitForTag("intro-continue", 10_000)
        compose.onNodeWithTag("intro-continue").performClick()
        waitForTag("sign-in", 5_000)
        assert(compose.onAllNodesWithTag("new-message").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("settings-button").assertIsDisplayed()
    }

    // The header while the email is checked

    @Test
    fun theHeaderShowsThatTheEmailIsBeingCheckedThenTheResult() {
        launch()
        openMessage("demo-2")

        // Right away: a quiet row saying the email is being checked.
        waitForTag("security-scanning", 5_000)
        compose.onNodeWithText("Checking this email…").assertIsDisplayed()
        screenshot("security-scanning")

        // Then the problems, in place of the row.
        waitForTag("security-findings")
        assert(compose.onAllNodesWithTag("security-scanning").fetchSemanticsNodes().isEmpty())
    }
}
