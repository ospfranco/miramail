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

/// The system's semantic colors, so every screen follows light/dark mode, high contrast and the platform's look.
enum Theme {
    static let text = Color(uiColor: .label)
    static let textSecondary = Color(uiColor: .secondaryLabel)
    static let background = Color(uiColor: .systemBackground)
    static let backgroundElement = Color(uiColor: .secondarySystemBackground)
    static let backgroundSelected = Color(uiColor: .separator)
    /// Behind grouped content (settings), with `groupedElement` for the cards on it.
    static let groupedBackground = Color(uiColor: .systemGroupedBackground)
    static let groupedElement = Color(uiColor: .secondarySystemGroupedBackground)
    static let linkPrimary = Color.accentColor
    /// The blue of the app icon, used as the app's tint.
    static let brand = Color(hex: "208AEF")

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
    var contentColor: Color { Color(hex: contentHexColor) }
}

/// Text styles shared by every screen, on the system's Dynamic Type scale.
enum TextStyle {
    case regular, small, smallBold, subtitle, link

    fileprivate var font: Font {
        switch self {
        case .regular: .body
        case .small: .subheadline
        case .smallBold: .subheadline.weight(.semibold)
        case .subtitle: .largeTitle.bold()
        case .link: .subheadline
        }
    }
}

extension View {
    func textStyle(_ style: TextStyle, color: Color = Theme.text) -> some View {
        font(style.font).foregroundStyle(color)
    }

    /// Liquid Glass where the system has it (iOS 26), a translucent material before that.
    @ViewBuilder
    func glassBackground<S: Shape>(in shape: S) -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular.interactive(), in: shape)
        } else {
            background(.regularMaterial, in: shape)
                .overlay(shape.stroke(Theme.backgroundSelected.opacity(0.5), lineWidth: 0.5))
                .shadow(color: .black.opacity(0.15), radius: 10, y: 3)
        }
    }
}

/// The sender's initial on a colored circle.
struct AvatarView: View {
    let from: String
    var size: CGFloat = 40

    var body: some View {
        let avatar = SenderAvatar(from)
        Circle()
            .fill(Color(hex: avatar.hexColor).gradient)
            .frame(width: size, height: size)
            .overlay {
                Text(avatar.initial)
                    .font(.system(size: size * 0.42, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
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

/// The sender's address when a sender check failed: the domain in red with the unsafe marker after it, like an unsafe link.
struct FailedSenderLine: View {
    let address: String

    var body: some View {
        let at = address.lastIndex(of: "@").map { address.index(after: $0) } ?? address.startIndex
        HStack(spacing: Spacing.one) {
            (Text(address[..<at]) + Text(address[at...]).foregroundStyle(Verdict.danger.color).fontWeight(.semibold))
                .textStyle(.small, color: Theme.textSecondary)
                .lineLimit(1)
                // Keep the domain in view when the address is too long.
                .truncationMode(.head)
            VerdictDot(verdict: .danger, size: 14)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(address), sender verification failed")
        .accessibilityIdentifier("sender-warning")
    }
}

/// The sender findings as small tags on an inbox row, in the words and colors of the email header's cards.
/// Side by side when they fit, else one under the other.
struct SenderTags: View {
    let labels: [SenderCheckLabel]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.one) { tags }
            VStack(alignment: .leading, spacing: Spacing.half) { tags }
        }
        .padding(.vertical, Spacing.half)
        // One element whose label lists the tags, so it is read with the rest of the inbox row.
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("sender-tags")
    }

    private var tags: some View {
        ForEach(labels, id: \.self) { SenderTag(label: $0) }
    }
}

private struct SenderTag: View {
    let label: SenderCheckLabel

    var body: some View {
        HStack(spacing: Spacing.half) {
            Text(label.verdict.glyph).fontWeight(.heavy).accessibilityHidden(true)
            Text(label.title).lineLimit(1)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(label.verdict.contentColor)
        .padding(.horizontal, Spacing.two)
        .padding(.vertical, Spacing.half)
        .background(label.verdict.color, in: Capsule())
    }
}

/// An email's subject, or "(no subject)" in the user's language when it has none.
func displaySubject(_ subject: String) -> String {
    subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? String(localized: "(no subject)") : subject
}
