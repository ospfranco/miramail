import XCTest

/// Drives the app in demo mode (see `Demo` in the app target), so no Google account is needed.
/// The security checks hit the real network, hence the generous timeouts.
@MainActor
final class SafemailUITests: XCTestCase {
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

    func testInboxListsDemoMessagesAndSignsOut() {
        XCTAssertTrue(app.staticTexts["demo@example.com"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["message-demo-1"].exists)
        XCTAssertTrue(app.buttons["message-demo-2"].exists)
        screenshot("inbox")

        app.buttons["sign-out"].tap()
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
        XCTAssertTrue(app.staticTexts["Welcome to Safemail"].waitForExistence(timeout: 10))
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
        XCTAssertFalse(app.buttons["sign-out"].exists)

        // Signed out, the settings screen still opens but offers no sign out.
        app.buttons["settings-button"].tap()
        XCTAssertTrue(app.switches["javascript-toggle"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["settings-sign-out"].exists)
        screenshot("settings-signed-out")
    }
}
