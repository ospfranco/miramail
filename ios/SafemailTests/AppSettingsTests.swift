import Foundation
import Testing
@testable import Safemail

@MainActor
struct AppSettingsTests {
    /// A throwaway store, so tests never touch the real preferences.
    private func makeDefaults() -> UserDefaults {
        let name = "safemail-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func emailJavaScriptIsOffByDefault() {
        #expect(AppSettings(defaults: makeDefaults()).allowEmailJavaScript == false)
    }

    @Test func emailJavaScriptChoiceIsPersisted() {
        let defaults = makeDefaults()
        let settings = AppSettings(defaults: defaults)
        settings.allowEmailJavaScript = true
        #expect(AppSettings(defaults: defaults).allowEmailJavaScript)
        settings.allowEmailJavaScript = false
        #expect(!AppSettings(defaults: defaults).allowEmailJavaScript)
    }
}
