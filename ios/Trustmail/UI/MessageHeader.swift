import SwiftUI

/// When the email's header gives way to a compact one: once the body is scrolled down, back at the top.
enum HeaderCollapse {
    /// How far the body must be scrolled before the header shrinks (points).
    static let collapseOffset: CGFloat = 48
    /// Near the top again, the full header returns (points). The gap to `collapseOffset` keeps it from flickering.
    static let expandOffset: CGFloat = 8
    /// The body must scroll at least this far for the header to shrink (points). Shrinking frees up to about this much
    /// height, so a shorter email could be pushed back to the top by it and bounce between the two headers.
    static let minimumScrollable: CGFloat = 240

    static func collapsed(current: Bool, offset: CGFloat, scrollable: CGFloat) -> Bool {
        current ? offset > expandOffset : offset > collapseOffset && scrollable > minimumScrollable
    }
}

/// The top of an email: subject, sender and date, or, once the body is scrolled, one compact row. Both keep the
/// sender's failed-check marker, so the warning never scrolls out of view.
struct MessageHeader: View {
    let message: MessageDetail
    let evaluation: SecurityEvaluation?
    let collapsed: Bool

    var body: some View {
        Group {
            if collapsed { compact } else { full }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(.easeInOut(duration: 0.2), value: collapsed)
    }

    private var senderFailed: Bool { evaluation?.senderFailed == true }

    private var full: some View {
        VStack(alignment: .leading, spacing: Spacing.three) {
            Text(message.subject)
                .font(.title2.weight(.bold))
                .foregroundStyle(Theme.text)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: Spacing.three) {
                AvatarView(from: message.from)
                VStack(alignment: .leading, spacing: Spacing.half) {
                    Text(senderName(message.from)).font(.headline).foregroundStyle(Theme.text).lineLimit(1)
                    if senderFailed {
                        FailedSenderLine(address: message.fromAddress)
                    } else {
                        Text(message.from).textStyle(.small, color: Theme.textSecondary).lineLimit(1)
                    }
                    Text(message.date).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(1)
                }
            }
            if evaluation == nil {
                Label("Scanning links…", systemImage: "shield.lefthalf.filled")
                    .textStyle(.small, color: Theme.textSecondary)
            }
        }
        .padding(Spacing.three)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("message-header")
    }

    private var compact: some View {
        HStack(spacing: Spacing.two) {
            AvatarView(from: message.from, size: 28)
            VStack(alignment: .leading, spacing: 0) {
                Text(message.subject).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                if senderFailed {
                    FailedSenderLine(address: message.fromAddress)
                } else {
                    Text(senderName(message.from)).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(1)
                }
            }
        }
        .padding(.horizontal, Spacing.three)
        .padding(.vertical, Spacing.two)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("message-header-compact")
    }
}
