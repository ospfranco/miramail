import Foundation
import Observation

/// User preferences, persisted on the device.
@MainActor
@Observable
final class AppSettings {
    private enum Key {
        static let allowEmailJavaScript = "allowEmailJavaScript"
        static let hasSeenIntro = "hasSeenIntro"
    }

    private let defaults: UserDefaults

    /// Whether scripts inside an email may run. Off by default: a script can change what the reader
    /// sees after the links were scanned. Safemail's own scanning script runs either way.
    var allowEmailJavaScript: Bool {
        didSet { defaults.set(allowEmailJavaScript, forKey: Key.allowEmailJavaScript) }
    }

    /// Whether the intro screen has been shown (or the user signed in without it), so it only appears once.
    var hasSeenIntro: Bool {
        didSet { defaults.set(hasSeenIntro, forKey: Key.hasSeenIntro) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        allowEmailJavaScript = defaults.bool(forKey: Key.allowEmailJavaScript)
        hasSeenIntro = defaults.bool(forKey: Key.hasSeenIntro)
    }
}
