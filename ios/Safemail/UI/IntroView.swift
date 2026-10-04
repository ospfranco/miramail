import SwiftUI

/// Shown once, before the first sign-in: what safemail does, and that Google sign-in comes next.
struct IntroView: View {
    let onContinue: () -> Void

    private let steps: [(title: String, text: String)] = [
        ("Open an email", "Safemail checks who really sent it and scans every link in it."),
        ("See the verdict", "A green, yellow or red mark sits next to each link, and the shield on the edge opens the full report."),
        ("Tap with confidence", "Risky links ask you to confirm before they open."),
    ]

    /// The preview's width over its height, so the rounded frame hugs the image.
    private static let previewAspectRatio: CGFloat = UIImage(named: "IntroPreview").map { $0.size.width / $0.size.height } ?? 1.2

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: Spacing.four) {
                    VStack(spacing: Spacing.two) {
                        Text("Welcome to safemail")
                            .font(.system(size: 28, weight: .semibold))
                            .foregroundStyle(Theme.text)
                            .accessibilityAddTraits(.isHeader)
                        Text("Safemail checks every email for phishing before you act on it.")
                            .textStyle(.regular, color: Theme.textSecondary)
                    }
                    .multilineTextAlignment(.center)

                    Image("IntroPreview")
                        .resizable()
                        .aspectRatio(Self.previewAspectRatio, contentMode: .fit)
                        .frame(maxWidth: 220 * Self.previewAspectRatio) // at most 220pt tall, and the frame hugs the image
                        .clipShape(RoundedRectangle(cornerRadius: Spacing.three))
                        .overlay(RoundedRectangle(cornerRadius: Spacing.three).stroke(Theme.backgroundSelected, lineWidth: 1))
                        .accessibilityLabel("Preview: an email with a safety mark next to each link and a shield on the edge")
                        .accessibilityIdentifier("intro-preview")

                    VStack(alignment: .leading, spacing: Spacing.three) {
                        ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                            HStack(alignment: .top, spacing: Spacing.three) {
                                Text("\(index + 1)")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 28, height: 28)
                                    .background(Theme.brand, in: Circle())
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: Spacing.half) {
                                    Text(step.title).textStyle(.smallBold)
                                    Text(step.text).textStyle(.small, color: Theme.textSecondary)
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Text("On the next step you'll sign in with your Google account, so safemail can read your Gmail.")
                        .textStyle(.small, color: Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("intro-google-note")
                }
                .padding(Spacing.three)
                .frame(maxWidth: Theme.maxContentWidth)
                .frame(maxWidth: .infinity)
            }

            Button(action: onContinue) {
                Text("Continue")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.three)
                    .background(Theme.brand, in: RoundedRectangle(cornerRadius: Spacing.three))
            }
            .padding(.horizontal, Spacing.three)
            .padding(.bottom, Spacing.two)
            .frame(maxWidth: Theme.maxContentWidth)
            .accessibilityIdentifier("intro-continue")
        }
        .background(Theme.background.ignoresSafeArea())
    }
}
