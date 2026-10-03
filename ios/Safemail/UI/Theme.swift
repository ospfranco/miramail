import SwiftUI
import UIKit

extension Color {
    init(hex: String) {
        let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0
        self.init(
            red: Double((value >> 16) & 0xff) / 255,
            green: Double((value >> 8) & 0xff) / 255,
            blue: Double(value & 0xff) / 255
        )
    }

    /// A color that switches between light and dark appearance.
    fileprivate init(light: String, dark: String) {
        self.init(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            let value = UInt32(hex, radix: 16) ?? 0
            return UIColor(
                red: CGFloat((value >> 16) & 0xff) / 255,
                green: CGFloat((value >> 8) & 0xff) / 255,
                blue: CGFloat(value & 0xff) / 255,
                alpha: 1
            )
        })
    }
}

enum Theme {
    static let text = Color(light: "000000", dark: "FFFFFF")
    static let background = Color(light: "FFFFFF", dark: "000000")
    static let backgroundElement = Color(light: "F0F0F3", dark: "212225")
    static let backgroundSelected = Color(light: "E0E1E6", dark: "2E3135")
    static let textSecondary = Color(light: "60646C", dark: "B0B4BA")
    static let linkPrimary = Color(hex: "3C87F7")

    static let maxContentWidth: CGFloat = 800
}

enum Spacing {
    static let half: CGFloat = 2
    static let one: CGFloat = 4
    static let two: CGFloat = 8
    static let three: CGFloat = 16
    static let four: CGFloat = 24
    static let five: CGFloat = 32
    static let six: CGFloat = 64
}

extension Verdict {
    var color: Color { Color(hex: hexColor) }
}

/// Text styles shared by every screen.
enum TextStyle {
    case regular, small, smallBold, subtitle, link

    fileprivate var font: Font {
        switch self {
        case .regular: .system(size: 16, weight: .medium)
        case .small: .system(size: 14, weight: .medium)
        case .smallBold: .system(size: 14, weight: .bold)
        case .subtitle: .system(size: 32, weight: .semibold)
        case .link: .system(size: 14)
        }
    }
}

extension View {
    func textStyle(_ style: TextStyle, color: Color = Theme.text) -> some View {
        font(style.font).foregroundStyle(color)
    }
}

struct VerdictDot: View {
    let verdict: Verdict
    var size: CGFloat = 18

    var body: some View {
        Circle()
            .fill(verdict.color)
            .frame(width: size, height: size)
            .overlay {
                Text(verdict.glyph)
                    .font(.system(size: size * 0.6, weight: .bold))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
    }
}
