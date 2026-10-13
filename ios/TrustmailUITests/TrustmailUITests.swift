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
        // In the inbox: the forged sender's domain is marked, and so is a sender you've never written with.
        func row(_ id: String, mentioning text: String) -> XCUIElement {
            app.buttons.matching(identifier: "message-\(id)").element(matching: NSPredicate(format: "label CONTAINS %@", text))
        }
        XCTAssertTrue(row("demo-2", mentioning: "sender verification failed").waitForExistence(timeout: 10))
        XCTAssertTrue(row("demo-2", mentioning: "First message from this sender").waitForExistence(timeout: 10))
        XCTAssertFalse(row("demo-1", mentioning: "sender verification failed").exists)
        XCTAssertFalse(row("demo-1", mentioning: "First message from this sender").exists)
        screenshot("inbox-warnings")

        app.buttons["message-demo-2"].tap()
        XCTAssertTrue(app.staticTexts["Action required: verify your account"].waitForExistence(timeout: 10))

        // Once every link and the sender have been checked, the problems show in the header, unsafe ones first.
        let findings = app.otherElements["security-findings"]
        XCTAssertTrue(findings.waitForExistence(timeout: 60), "scan never finished")
        XCTAssertEqual(findings.buttons.allElementsBoundByIndex.map(\.label).filter(titles.contains), [
            "Sender verification failed", "Unsafe links", "First message from this sender", "Suspicious links",
        ])
        // Each one opens to explain itself in plain words.
        findings.buttons["First message from this sender"].tap()
        XCTAssertTrue(findings.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "This is the first email you get from this address.")).firstMatch.waitForExistence(timeout: 5))
        // The header marks the sender's domain like an unsafe link.
        XCTAssertTrue(app.descendants(matching: .any)["sender-warning"].exists)
        screenshot("message-scanned")

        // The email's own <script> must not have run.
        XCTAssertTrue(app.webViews.links["paypal.com/login"].exists)
        XCTAssertTrue(app.webViews.staticTexts["Scripts in this email are blocked."].exists)

        app.webViews.links["paypal.com/login"].tap()
        let alert = app.alerts["This link looks unsafe"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        let warning = alert.staticTexts.element(boundBy: 1).label
        XCTAssertTrue(warning.hasPrefix("Shown as: paypal.com/login\nGoes to: http://login.paypa1-secure.example.net/verify\n\n"))
        // Why, in plain words.
        XCTAssertTrue(warning.contains("The link's text shows one website, but it takes you to another."))
        screenshot("link-warning")
        alert.buttons["Cancel"].tap()
        XCTAssertFalse(alert.exists)
    }

    /// The titles of the problems the header can show.
    private let titles: Set<String> = [
        "Sender not verified", "Sender verification failed", "First message from this sender", "Sender history unknown",
        "Unsafe links", "Suspicious links",
    ]

    func testTheHeaderShrinksWhileReadingALongEmail() {
        app.buttons["message-demo-1"].tap()
        XCTAssertTrue(app.otherElements["message-header"].waitForExistence(timeout: 10))
        // Wait for the check to finish: the header changes height then, and a swipe before the page is ready is lost.
        let result = app.descendants(matching: .any).matching(NSPredicate(format: "identifier IN %@", ["security-safe", "security-findings"])).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 60), "scan never finished")
        app.webViews.firstMatch.swipeUp()
        XCTAssertTrue(app.otherElements["message-header-compact"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Hello from the Trustmail team"].exists, "the subject stays in view")
        screenshot("message-header-compact")
        app.webViews.firstMatch.swipeDown()
        app.webViews.firstMatch.swipeDown()
        XCTAssertTrue(app.otherElements["message-header"].waitForExistence(timeout: 5))
    }

    func testPlainTextEmailFromKnownSender() {
        app.buttons["message-demo-1"].tap()
        XCTAssertTrue(app.staticTexts["Hello from the Trustmail team"].waitForExistence(timeout: 10))
        // A verified, known sender: nothing about the sender in the header. (Its one link is checked over the real
        // network, so whether the header is green or warns about the link depends on the network.)
        let result = app.descendants(matching: .any).matching(NSPredicate(format: "identifier IN %@", ["security-safe", "security-findings"])).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 60), "scan never finished")
        for title in titles where title.hasPrefix("Sender") || title.hasPrefix("First") {
            XCTAssertFalse(app.buttons[title].exists, title)
        }
        // Plain text is linkified.
        XCTAssertTrue(app.webViews.links["https://example.com/how-it-works"].exists)
        XCTAssertFalse(app.descendants(matching: .any)["sender-warning"].exists)
        screenshot("plain-text-scanned")
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
        // In a settings form the switch is at the trailing end of its row; tapping the middle hits the label.
        app.switches["javascript-toggle"].coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        XCTAssertEqual(app.switches["javascript-toggle"].value as? String, "1")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["message-demo-2"].tap()
        let findings = app.otherElements["security-findings"]
        XCTAssertTrue(findings.waitForExistence(timeout: 60), "scan never finished")
        XCTAssertTrue(app.webViews.staticTexts["Scripts in this email are running."].exists)
        // The links are judged the same with the email's scripts on.
        XCTAssertTrue(findings.buttons["Unsafe links"].exists)
        screenshot("message-javascript-on")
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
        XCTAssertTrue(app.otherElements["security-findings"].waitForExistence(timeout: 60), "scan never finished")
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

    // MARK: - Saved inbox

    func testOfflineLaunchShowsTheSavedInbox() {
        app.terminate()
        app.launchArguments = ["-demo", "-demo-offline"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["saved-inbox"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["message-demo-1"].exists)
        // The saved copy keeps its markers.
        let marked = app.buttons.matching(identifier: "message-demo-2").element(matching: NSPredicate(format: "label CONTAINS %@", "First message from this sender"))
        XCTAssertTrue(marked.exists)
        screenshot("inbox-saved")
    }

    func testOnlineLaunchShowsNoSavedInboxNote() {
        XCTAssertTrue(app.buttons["message-demo-1"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.descendants(matching: .any)["saved-inbox"].exists)
    }

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

    // MARK: - The header while the email is checked

    func testTheHeaderShowsThatTheEmailIsBeingCheckedThenTheResult() {
        openMessage("demo-2")

        // Right away: a quiet row saying the email is being checked.
        let scanning = app.descendants(matching: .any)["security-scanning"]
        XCTAssertTrue(scanning.waitForExistence(timeout: 5), "the header should say the email is being checked")
        XCTAssertTrue(app.staticTexts["Checking this email…"].exists)
        screenshot("security-scanning")

        // Then the problems, in place of the row.
        XCTAssertTrue(app.otherElements["security-findings"].waitForExistence(timeout: 60), "scan never finished")
        XCTAssertFalse(scanning.exists)
    }
}
