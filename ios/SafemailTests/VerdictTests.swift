import Testing
@testable import Safemail

struct VerdictTests {
    @Test func worstVerdictWins() {
        #expect(Verdict.worst(of: []) == .safe)
        #expect(Verdict.worst(of: [.safe, .safe]) == .safe)
        #expect(Verdict.worst(of: [.safe, .warning, .safe]) == .warning)
        #expect(Verdict.worst(of: [.warning, .danger, .safe]) == .danger)
    }

    @Test func glyphsAndColors() {
        #expect(Verdict.safe.glyph == "✓")
        #expect(Verdict.warning.glyph == "!")
        #expect(Verdict.danger.glyph == "✕")
        #expect(Verdict.danger.hexColor == "E5484D")
    }

    @Test func everyCheckKindHasALabel() {
        for kind in LinkCheckKind.allCases { #expect(!kind.label.isEmpty) }
        #expect(LinkCheckKind.dnsSpoofing.label == "DNS")
    }
}
