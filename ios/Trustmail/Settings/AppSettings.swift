import Foundation
import Observation

/// User preferences, persisted on the device.
@MainActor
@Observable
final class AppSettings {
    private enum Key {
        static let allowEmailJavaScript = "allowEmailJavaScript"
        static let hasSeenIntro = "hasSeenIntro"
        static let ignoreFooterLinks = "ignoreFooterLinks"
    }

    private let defaults: UserDefaults

    /// Whether scripts inside an email may run. Off by default: a script can change what the reader
    /// sees after the links were scanned. Miramail's own scanning script runs either way.
    var allowEmailJavaScript: Bool {
        didSet { defaults.set(allowEmailJavaScript, forKey: Key.allowEmailJavaScript) }
    }

    /// Whether suspicious links in an email's footer (unsubscribe, privacy policy, social media) are left
    /// out of the email's verdict. Off by default. Footer links are still scanned and badged, and an unsafe
    /// one still counts, so wrapping a phishing link in a footer can't hide it.
    var ignoreFooterLinks: Bool {
        didSet { defaults.set(ignoreFooterLinks, forKey: Key.ignoreFooterLinks) }
    }

    /// Whether the intro screen has been shown (or the user signed in without it), so it only appears once.
    var hasSeenIntro: Bool {
        didSet { defaults.set(hasSeenIntro, forKey: Key.hasSeenIntro) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        allowEmailJavaScript = defaults.bool(forKey: Key.allowEmailJavaScript)
        hasSeenIntro = defaults.bool(forKey: Key.hasSeenIntro)
        ignoreFooterLinks = defaults.bool(forKey: Key.ignoreFooterLinks)
    }
}
