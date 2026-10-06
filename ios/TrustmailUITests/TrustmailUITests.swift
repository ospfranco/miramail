import XCTest

/// Drives the app in demo mode (see `Demo` in the app target), so no Google account is needed.
/// The security checks hit the real network, hence the generous timeouts.
@MainActor
final class TrustmailUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-demo"]
        app.launch()
    }

    private func screenshot(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] {
            try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("ios-\(name).png"))
        }
    }

    func testInboxListsDemoMessagesWithAPencilAndSettingsButNoSignOut() {
        XCTAssertTrue(app.staticTexts["demo@example.com"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["message-demo-1"].exists)
        XCTAssertTrue(app.buttons["message-demo-2"].exists)
        XCTAssertTrue(app.buttons["new-message"].exists)
        XCTAssertTrue(app.buttons["settings-button"].exists)
        XCTAssertFalse(app.buttons["sign-out"].exists, "signing out lives in the settings now")
        screenshot("inbox")

        // Signing out and back in, via the settings.
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.buttons["settings-sign-out"].waitForExistence(timeout: 5))
        app.buttons["settings-sign-out"].tap()
        XCTAssertTrue(app.buttons["sign-in"].waitForExistence(timeout: 5))
        app.buttons["sign-in"].tap()
        XCTAssertTrue(app.staticTexts["demo@example.com"].waitForExistence(timeout: 5))
    }

    func testPhishingEmailIsFlaggedAndRiskyLinksAskForConfirmation() {
        app.buttons["message-demo-2"].tap()
        XCTAssertTrue(app.staticTexts["Action required: verify your account"].waitForExistence(timeout: 10))

        // The panel tab only appears once every link and the sender have been checked.
        let tab = app.buttons["security-tab"]
        XCTAssertTrue(tab.waitForExistence(timeout: 60), "scan never finished")
        XCTAssertEqual(tab.label, "Security: This email looks dangerous")
        screenshot("message-scanned")

        // The email's own <script> must not have run.
        XCTAssertTrue(app.webViews.links["paypal.com/login"].exists)
        XCTAssertTrue(app.webViews.staticTexts["Scripts in this email are blocked."].exists)

        tab.tap()
        XCTAssertTrue(app.staticTexts["This email looks dangerous"].waitForExistence(timeout: 5))
        screenshot("security-panel")
        XCTAssertTrue(app.staticTexts["Sender verification failed"].exists)
        XCTAssertTrue(app.staticTexts["First message from this sender"].exists)
        // Three links, including the javascript: one, with JavaScript in emails off (the default).
        XCTAssertTrue(app.staticTexts["3 links scanned"].exists)
        app.buttons["security-close"].tap()

        app.webViews.links["paypal.com/login"].tap()
        let alert = app.alerts["This link looks unsafe"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts.element(boundBy: 1).label.contains("Text shows paypal.com but the link goes to login.paypa1-secure.example.net."))
        screenshot("link-warning")
        alert.buttons["Cancel"].tap()
        XCTAssertFalse(alert.exists)
    }

    func testPlainTextEmailFromKnownSender() {
        app.buttons["message-demo-1"].tap()
        XCTAssertTrue(app.staticTexts["Hello from the Trustmail team"].waitForExistence(timeout: 10))
        let tab = app.buttons["security-tab"]
        XCTAssertTrue(tab.waitForExistence(timeout: 60), "scan never finished")
        // Plain text is linkified, and the verified known sender is not what makes this email risky.
        XCTAssertTrue(app.webViews.links["https://example.com/how-it-works"].exists)
        tab.tap()
        XCTAssertTrue(app.staticTexts["Sender verified"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Known sender"].exists)
        screenshot("plain-text-panel")
    }

    private func openSettings() {
        XCTAssertTrue(app.buttons["settings-button"].waitForExistence(timeout: 10))
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.switches["javascript-toggle"].waitForExistence(timeout: 5))
    }

    func testSettingsButtonOpensSettingsWithJavaScriptOffByDefault() {
        openSettings()
        XCTAssertEqual(app.switches["javascript-toggle"].value as? String, "0")
        XCTAssertTrue(app.staticTexts["Allow JavaScript in emails"].exists)
        XCTAssertEqual(app.switches["footer-links-toggle"].value as? String, "0")
        XCTAssertTrue(app.staticTexts["Ignore footer links"].exists)
        XCTAssertTrue(app.buttons["settings-sign-out"].exists)
        screenshot("settings")
    }

    func testEnablingJavaScriptLetsTheEmailsOwnScriptsRunAndKeepsEveryLinkScanned() {
        openSettings()
        app.switches["javascript-toggle"].tap()
        XCTAssertEqual(app.switches["javascript-toggle"].value as? String, "1")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["message-demo-2"].tap()
        let tab = app.buttons["security-tab"]
        XCTAssertTrue(tab.waitForExistence(timeout: 60), "scan never finished")
        XCTAssertTrue(app.webViews.staticTexts["Scripts in this email are running."].exists)
        screenshot("message-javascript-on")

        tab.tap()
        XCTAssertTrue(app.staticTexts["3 links scanned"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["This email looks dangerous"].exists)
    }

    func testSettingsHasSignOutThatReturnsToTheSignInScreen() {
        openSettings()
        XCTAssertTrue(app.staticTexts["demo@example.com"].exists)
        app.buttons["settings-sign-out"].tap()
        // Back on the inbox, now signed out.
        XCTAssertTrue(app.buttons["sign-in"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["new-message"].exists, "nothing can be sent while signed out")

        // Signed out, the settings screen still opens but offers no sign out.
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.switches["javascript-toggle"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["settings-sign-out"].exists)
        screenshot("settings-signed-out")
    }

    // MARK: - Floating action bar and compose

    private func openMessage(_ id: String) {
        XCTAssertTrue(app.buttons["message-\(id)"].waitForExistence(timeout: 10))
        app.buttons["message-\(id)"].tap()
        XCTAssertTrue(app.buttons["action-archive"].waitForExistence(timeout: 10), "the action bar never appeared")
    }

    private func assertBackOnInbox(without id: String, notice: String) {
        XCTAssertTrue(app.staticTexts["notice"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["notice"].label, notice)
        XCTAssertTrue(app.buttons["new-message"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["message-\(id)"].exists, "\(id) is still in the inbox")
    }

    func testTheEmailHasAFloatingBarWithFourButtons() {
        openMessage("demo-2")
        for id in ["action-delete", "action-archive", "action-move", "action-reply"] {
            XCTAssertTrue(app.buttons[id].exists, id)
        }
        XCTAssertTrue(app.buttons["security-tab"].waitForExistence(timeout: 60), "scan never finished")
        screenshot("message-scanned")
    }

    func testArchiveTakesTheEmailOutOfTheInbox() {
        openMessage("demo-1")
        app.buttons["action-archive"].tap()
        assertBackOnInbox(without: "demo-1", notice: "Archived")
        XCTAssertTrue(app.buttons["message-demo-2"].exists)
    }

    func testDeleteMovesTheEmailToTrash() {
        openMessage("demo-2")
        app.buttons["action-delete"].tap()
        assertBackOnInbox(without: "demo-2", notice: "Moved to Trash")
        XCTAssertTrue(app.buttons["message-demo-1"].exists)
    }

    func testMoveOffersTheLabelsAndSpam() {
        openMessage("demo-1")
        app.buttons["action-move"].tap()
        XCTAssertTrue(app.buttons["Work"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Receipts"].exists)
        XCTAssertTrue(app.buttons["Spam"].exists)
        screenshot("move-chooser")
        app.buttons["Work"].tap()
        assertBackOnInbox(without: "demo-1", notice: "Moved to Work")
    }

    func testCancellingMoveLeavesTheEmailWhereItIs() {
        openMessage("demo-1")
        app.buttons["action-move"].tap()
        XCTAssertTrue(app.buttons["Work"].waitForExistence(timeout: 5))
        // The chooser is a popover (no Cancel button): tapping outside it dismisses it.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.7)).tap()
        XCTAssertFalse(app.buttons["Work"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["action-move"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["notice"].exists)
    }

    func testReplyOpensAPrefilledComposeScreenAndSends() {
        openMessage("demo-1")
        app.buttons["action-reply"].tap()
        XCTAssertTrue(app.navigationBars["Reply"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.textFields["compose-to"].value as? String, "hello@example.com")
        XCTAssertEqual(app.textFields["compose-subject"].value as? String, "Re: Hello from the Trustmail team")

        app.textViews["compose-body"].tap()
        app.textViews["compose-body"].typeText("Thanks for the welcome!")
        screenshot("compose")
        XCTAssertTrue(app.buttons["compose-send"].isEnabled)
        app.buttons["compose-send"].tap()

        // Back on the email, with a confirmation.
        XCTAssertTrue(app.staticTexts["notice"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["notice"].label, "Message sent")
        XCTAssertTrue(app.buttons["action-reply"].exists)
    }

    func testSendNeedsAValidRecipient() {
        openMessage("demo-1")
        app.buttons["action-reply"].tap()
        XCTAssertTrue(app.textFields["compose-to"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["compose-send"].isEnabled)

        app.textFields["compose-to"].tap()
        app.textFields["compose-to"].typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 40))
        XCTAssertFalse(app.buttons["compose-send"].isEnabled)

        app.textFields["compose-to"].typeText("someone@example.org")
        XCTAssertTrue(app.buttons["compose-send"].isEnabled)
    }

    // MARK: - Intro

    private func relaunchSignedOut() {
        app.terminate()
        app.launchArguments = ["-demo", "-demo-signed-out"]
        app.launch()
    }

    func testFirstLaunchShowsTheIntroBeforeSignIn() {
        relaunchSignedOut()
        XCTAssertTrue(app.staticTexts["Welcome to Trustmail"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.images["intro-preview"].exists)
        XCTAssertTrue(app.staticTexts["Open an email"].exists)
        XCTAssertTrue(app.staticTexts["See the verdict"].exists)
        XCTAssertTrue(app.staticTexts["Tap with confidence"].exists)
        XCTAssertTrue(app.staticTexts["intro-google-note"].label.contains("sign in with your Google account"))
        // Not signed in yet: no inbox, no sign-in button until the user continues.
        XCTAssertFalse(app.buttons["sign-in"].exists)
        screenshot("intro")

        app.buttons["intro-continue"].tap()
        XCTAssertTrue(app.buttons["sign-in"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Welcome to Trustmail"].exists)

        app.buttons["sign-in"].tap()
        XCTAssertTrue(app.staticTexts["demo@example.com"].waitForExistence(timeout: 5))
    }

    func testSignedInUsersNeverSeeTheIntro() {
        XCTAssertTrue(app.staticTexts["demo@example.com"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Welcome to Trustmail"].exists)
        // ...even after signing out.
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.buttons["settings-sign-out"].waitForExistence(timeout: 5))
        app.buttons["settings-sign-out"].tap()
        XCTAssertTrue(app.buttons["sign-in"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Welcome to Trustmail"].exists)
    }

    // MARK: - The pencil: a new email

    func testThePencilOpensAnEmptyComposeScreenAndSends() {
        XCTAssertTrue(app.buttons["new-message"].waitForExistence(timeout: 10))
        app.buttons["new-message"].tap()
        XCTAssertTrue(app.navigationBars["New message"].waitForExistence(timeout: 5))
        XCTAssertTrue((app.textFields["compose-to"].value as? String ?? "").isEmpty, "a new email starts empty")
        XCTAssertFalse(app.buttons["compose-send"].isEnabled, "no recipient yet")

        app.textFields["compose-to"].tap()
        app.textFields["compose-to"].typeText("friend@example.org")
        app.textFields["compose-subject"].tap()
        app.textFields["compose-subject"].typeText("Lunch?")
        app.textViews["compose-body"].tap()
        app.textViews["compose-body"].typeText("Are you free on Friday?")
        screenshot("compose-new")
        XCTAssertTrue(app.buttons["compose-send"].isEnabled)
        app.buttons["compose-send"].tap()

        // Back on the inbox, with a confirmation.
        XCTAssertTrue(app.staticTexts["notice"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["notice"].label, "Message sent")
        XCTAssertTrue(app.buttons["new-message"].exists)
    }

    func testSignedOutThereIsNoPencil() {
        relaunchSignedOut()
        XCTAssertTrue(app.buttons["intro-continue"].waitForExistence(timeout: 10))
        app.buttons["intro-continue"].tap()
        XCTAssertTrue(app.buttons["sign-in"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["new-message"].exists)
        XCTAssertTrue(app.buttons["settings-button"].exists)
    }

    // MARK: - The security tab while scanning

    func testTheTabShowsASpinnerWhileTheEmailIsScannedThenTheVerdict() {
        openMessage("demo-1")

        // Right away: a tab with a spinner that doesn't open anything yet.
        let scanning = app.buttons["security-scanning"]
        XCTAssertTrue(scanning.waitForExistence(timeout: 5), "the tab should be there while scanning")
        XCTAssertEqual(scanning.label, "Security: scanning links")
        XCTAssertTrue(app.staticTexts["Scanning links…"].exists)
        screenshot("security-scanning")
        scanning.tap()
        XCTAssertFalse(app.buttons["security-close"].exists, "the panel has nothing to show yet")

        // Then the same tab turns into the verdict, and opens.
        let done = app.buttons["security-tab"]
        XCTAssertTrue(done.waitForExistence(timeout: 60), "scan never finished")
        XCTAssertTrue(done.label.hasPrefix("Security: "))
        XCTAssertFalse(app.buttons["security-scanning"].exists)
        done.tap()
        XCTAssertTrue(app.buttons["security-close"].waitForExistence(timeout: 5))
    }
}
