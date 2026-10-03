import Foundation
import Observation

/// User preferences, persisted on the device.
@MainActor
@Observable
final class AppSettings {
    private enum Key {
        static let allowEmailJavaScript = "allowEmailJavaScript"
    }

    private let defaults: UserDefaults

    /// Whether scripts inside an email may run. Off by default: a script can change what the reader
    /// sees after the links were scanned. Safemail's own scanning script runs either way.
    var allowEmailJavaScript: Bool {
        didSet { defaults.set(allowEmailJavaScript, forKey: Key.allowEmailJavaScript) }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        allowEmailJavaScript = defaults.bool(forKey: Key.allowEmailJavaScript)
    }
}
