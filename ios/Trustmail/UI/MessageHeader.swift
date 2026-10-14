import SwiftUI

/// When the email's header gives way to a compact one: once the body is scrolled down, back at the top.
enum HeaderCollapse {
    /// How far the body must be scrolled before the header shrinks (points).
    static let collapseOffset: CGFloat = 48
    /// Near the top again, the full header returns (points). The gap to `collapseOffset` keeps it from flickering.
    static let expandOffset: CGFloat = 8
    /// The body must scroll at least this far for the header to shrink (points), and more than the full header is tall.
    /// Shrinking frees up to that much height, so a shorter email could be pushed back to the top by it and bounce
    /// between the two headers.
    static let minimumScrollable: CGFloat = 240

    static func collapsed(current: Bool, offset: CGFloat, scrollable: CGFloat, headerHeight: CGFloat = 0) -> Bool {
        current ? offset > expandOffset : offset > collapseOffset && scrollable > max(minimumScrollable, headerHeight)
    }
}

/// The top of an email: subject, sender, date and what the scan found, or, once the body is scrolled, one compact row.
/// Problems are shown as bright cards that open to explain them in plain words. The compact row keeps the sender's failed-check
/// marker and the verdict's color, so a warning never scrolls out of view.
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
            Text(displaySubject(message.subject))
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
            findings
        }
        .padding(Spacing.three)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("message-header")
    }

    private var compact: some View {
        HStack(spacing: Spacing.two) {
            AvatarView(from: message.from, size: 28)
            VStack(alignment: .leading, spacing: 0) {
                Text(displaySubject(message.subject)).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                if senderFailed {
                    FailedSenderLine(address: message.fromAddress)
                } else {
                    Text(senderName(message.from)).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let evaluation, evaluation.verdict != .safe {
                VerdictDot(verdict: evaluation.verdict, size: 22)
                    .accessibilityHidden(false)
                    .accessibilityLabel(evaluation.summary)
            }
        }
        .padding(.horizontal, Spacing.three)
        .padding(.vertical, Spacing.two)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("message-header-compact")
    }

    /// While the email is checked, a quiet row; then a short green line, or one bright card per problem.
    @ViewBuilder private var findings: some View {
        if let evaluation {
            if evaluation.findings.isEmpty {
                Label(evaluation.summary, systemImage: "checkmark.shield.fill")
                    .textStyle(.small, color: Verdict.safe.color)
                    .accessibilityIdentifier("security-safe")
            } else {
                VStack(spacing: Spacing.two) {
                    ForEach(evaluation.findings) { FindingCard(check: $0) }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("security-findings")
            }
        } else {
            HStack(spacing: Spacing.two) {
                ProgressView().controlSize(.small)
                Text("Checking this email…").textStyle(.small, color: Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("security-scanning")
        }
    }
}

/// One problem the scan found, on its verdict's color so it can't be missed. Only its title shows at first, so a few
/// of them still leave room for the email; tapping it opens the plain-words explanation.
private struct FindingCard: View {
    let check: SecurityCheck
    @State private var expanded = false

    var body: some View {
        Button { withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() } } label: {
            VStack(alignment: .leading, spacing: Spacing.one) {
                HStack(spacing: Spacing.two) {
                    Text(check.verdict.glyph).font(.body.weight(.heavy)).accessibilityHidden(true)
                    Text(check.label).font(.subheadline.weight(.bold))
                    Spacer(minLength: 0)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").font(.footnote.weight(.bold)).accessibilityHidden(true)
                }
                if expanded {
                    Text(check.detail).font(.footnote).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                }
            }
            .foregroundStyle(check.verdict.contentColor)
            .padding(.horizontal, Spacing.three)
            .padding(.vertical, Spacing.two)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(check.verdict.color, in: RoundedRectangle(cornerRadius: Spacing.three))
        }
        .buttonStyle(.plain)
        .accessibilityHint(expanded ? Text(verbatim: "") : Text("Explains this warning"))
    }
}
