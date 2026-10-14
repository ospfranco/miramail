import Foundation
import Testing
@testable import Trustmail

/// The strings come from scripts/strings.json; these check what the apps got from it (twin of LocalizationTest on Android).
/// The tests run in English, so a string from the app is also its key in the German table.
struct LocalizationTests {
    private static let germanBundle = Bundle(path: Bundle.main.path(forResource: "de", ofType: "lproj")!)!

    private func german(_ key: String) -> String {
        Self.germanBundle.localizedString(forKey: key, value: "missing: \(key)", table: nil)
    }

    private func placeholders(_ text: String) -> Int {
        text.matches(of: /%(\d+\$)?@/).count
    }

    @Test func everyStringIsTranslatedWithTheSamePlaceholders() throws {
        let url = try #require(Self.germanBundle.url(forResource: "Localizable", withExtension: "strings"))
        let table = try #require(NSDictionary(contentsOf: url) as? [String: String])
        #expect(table.count > 50)
        for (key, value) in table {
            #expect(!value.trimmingCharacters(in: .whitespaces).isEmpty, "\(key) is empty in German")
            #expect(placeholders(key) == placeholders(value), "\(key)")
        }
    }

    @Test func theSecurityCardsSpeakGerman() {
        #expect(SenderCheckLabel.allCases.map { german($0.title) } == [
            "Absender bestätigt",
            "Absender nicht bestätigt",
            "Absenderprüfung fehlgeschlagen",
            "Bekannter Absender",
            "Erste E-Mail von diesem Absender",
            "Absenderverlauf unbekannt",
        ])
        #expect(LinkFindingLabel.allCases.map { german($0.title) } == ["Unsichere Links", "Verdächtige Links"])
        #expect(german(SenderCheckLabel.known.description) == "Du hast mit dieser Adresse schon E-Mails ausgetauscht.")
    }

    @Test func noticesFillInTheirPlaceholderInGerman() {
        #expect(String(format: german("Moved to %@"), "Work") == "Nach Work verschoben")
        #expect(german(MessageAction.archive.notice) == "Archiviert")
    }
}
