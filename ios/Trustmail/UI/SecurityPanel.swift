import SwiftUI

/// A tab on the right edge that opens a slide-in panel with every check and per-link result.
/// While the email is still being scanned (`evaluation` is nil) the tab shows a spinner and does nothing.
struct SecurityPanel: View {
    let evaluation: SecurityEvaluation?
    let links: [ScannedLink]

    @State private var open = false

    var body: some View {
        GeometryReader { geometry in
            let panelWidth = min(geometry.size.width * 0.85, 360)
            ZStack(alignment: .trailing) {
                tab
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, geometry.size.height * 0.4)

                Color.black.opacity(open ? 0.3 : 0)
                    .ignoresSafeArea()
                    .allowsHitTesting(open)
                    .onTapGesture { open = false }

                if let evaluation {
                    panel(evaluation, bottomInset: geometry.safeAreaInsets.bottom)
                        .frame(width: panelWidth)
                        .offset(x: open ? 0 : panelWidth + 24) // + room for the shadow
                        .accessibilityHidden(!open)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
            .animation(.easeInOut(duration: 0.22), value: open)
        }
    }

    private var tab: some View {
        Button { if evaluation != nil { open = true } } label: {
            VStack(spacing: Spacing.one) {
                Text("🛡️").font(.system(size: 14))
                if let evaluation {
                    Text(evaluation.verdict.glyph).font(.system(size: 14, weight: .bold)).foregroundStyle(.white)
                } else {
                    ProgressView().controlSize(.small).tint(.white).frame(height: 18)
                }
            }
            .frame(width: 32)
            .padding(.vertical, Spacing.three)
            .background(
                evaluation?.verdict.color ?? Theme.scanning,
                in: UnevenRoundedRectangle(topLeadingRadius: Spacing.three, bottomLeadingRadius: Spacing.three)
            )
            .shadow(color: .black.opacity(0.2), radius: 4, x: -1, y: 1)
            .animation(.easeInOut(duration: 0.2), value: evaluation)
        }
        .accessibilityLabel(evaluation.map { "Security: \($0.summary)" } ?? "Security: scanning links")
        // Tests (and anything else that wants to know) can tell the two states apart.
        .accessibilityIdentifier(evaluation == nil ? "security-scanning" : "security-tab")
    }

    private func panel(_ evaluation: SecurityEvaluation, bottomInset: CGFloat) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.three) {
                HStack {
                    Text("SECURITY").textStyle(.smallBold, color: Theme.textSecondary)
                    Spacer()
                    Button("Close") { open = false }
                        .textStyle(.link, color: Theme.linkPrimary)
                        .accessibilityIdentifier("security-close")
                }

                HStack(spacing: Spacing.two) {
                    VerdictDot(verdict: evaluation.verdict, size: 28)
                    Text(evaluation.summary).font(.title3.weight(.semibold)).foregroundStyle(Theme.text)
                }

                ForEach(evaluation.checks) { check in
                    HStack(alignment: .top, spacing: Spacing.two) {
                        VerdictDot(verdict: check.verdict)
                        VStack(alignment: .leading, spacing: Spacing.half) {
                            Text(check.label).textStyle(.smallBold)
                            Text(check.detail).textStyle(.small, color: Theme.textSecondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(Spacing.three)
                    .background(Theme.groupedElement, in: RoundedRectangle(cornerRadius: Spacing.three))
                }

                if !links.isEmpty {
                    Text("LINKS").textStyle(.smallBold, color: Theme.textSecondary).padding(.top, Spacing.two)
                    ForEach(links) { link in
                        HStack(alignment: .top, spacing: Spacing.two) {
                            VerdictDot(verdict: link.verdict, size: 16)
                            VStack(alignment: .leading, spacing: Spacing.half) {
                                Text(link.host.isEmpty ? link.url : link.host).textStyle(.small).lineLimit(1)
                                ForEach(Array(link.checks.enumerated()), id: \.offset) { _, check in
                                    HStack(alignment: .top, spacing: Spacing.one) {
                                        VerdictDot(verdict: check.verdict, size: 12)
                                        (Text("\(check.kind.label):").fontWeight(.bold) + Text(" \(check.detail)"))
                                            .textStyle(.small, color: Theme.textSecondary)
                                    }
                                    .padding(.top, Spacing.half)
                                }
                            }
                            Spacer(minLength: 0)
                        }
                    }
                }
            }
            .padding(Spacing.three)
            .padding(.bottom, bottomInset + Spacing.four)
        }
        .background(Theme.groupedBackground.ignoresSafeArea(edges: .vertical))
        .shadow(color: .black.opacity(0.25), radius: 12)
    }
}
