import Foundation
import Testing
@testable import Trustmail

@MainActor
struct AppSettingsTests {
    /// A throwaway store, so tests never touch the real preferences.
    private func makeDefaults() -> UserDefaults {
        let name = "trustmail-tests-\(UUID().uuidString)"
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

    @Test func footerLinksCountByDefault() {
        #expect(AppSettings(defaults: makeDefaults()).ignoreFooterLinks == false)
    }

    @Test func footerLinksChoiceIsPersisted() {
        let defaults = makeDefaults()
        let settings = AppSettings(defaults: defaults)
        settings.ignoreFooterLinks = true
        #expect(AppSettings(defaults: defaults).ignoreFooterLinks)
        settings.ignoreFooterLinks = false
        #expect(!AppSettings(defaults: defaults).ignoreFooterLinks)
    }

    @Test func introIsShownUntilSeen() {
        let defaults = makeDefaults()
        let settings = AppSettings(defaults: defaults)
        #expect(!settings.hasSeenIntro)
        settings.hasSeenIntro = true
        #expect(AppSettings(defaults: defaults).hasSeenIntro)
    }

    /// Pinned on both platforms: the addresses the stores and Google's consent screen list.
    @Test func privacyPolicyAndTermsLinkToTheWebsite() {
        #expect(AppSettings.privacyPolicyURL.absoluteString == "https://miramail.me/privacy")
        #expect(AppSettings.termsOfUseURL.absoluteString == "https://miramail.me/terms")
    }
}
