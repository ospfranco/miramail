import SwiftUI

/// Shown once, before the first sign-in: what Trustmail does, and that Google sign-in comes next.
struct IntroView: View {
    let onContinue: () -> Void

    private let steps: [(title: String, text: String)] = [
        ("Open an email", "Trustmail checks who really sent it and scans every link in it."),
        ("See the verdict", "A green, yellow or red mark sits next to each link, and anything wrong is explained at the top of the email."),
        ("Tap with confidence", "Risky links ask you to confirm before they open."),
    ]

    /// The preview's width over its height, so the rounded frame hugs the image.
    private static let previewAspectRatio: CGFloat = UIImage(named: "IntroPreview").map { $0.size.width / $0.size.height } ?? 1.2

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: Spacing.four) {
                    VStack(spacing: Spacing.two) {
                        Text("Welcome to Trustmail")
                            .font(.largeTitle.bold())
                            .foregroundStyle(Theme.text)
                            .accessibilityAddTraits(.isHeader)
                        Text("Trustmail checks every email for phishing before you act on it.")
                            .textStyle(.regular, color: Theme.textSecondary)
                    }
                    .multilineTextAlignment(.center)

                    Image("IntroPreview")
                        .resizable()
                        .aspectRatio(Self.previewAspectRatio, contentMode: .fit)
                        .frame(maxWidth: 220 * Self.previewAspectRatio) // at most 220pt tall, and the frame hugs the image
                        .clipShape(RoundedRectangle(cornerRadius: Spacing.three))
                        .overlay(RoundedRectangle(cornerRadius: Spacing.three).stroke(Theme.backgroundSelected, lineWidth: 0.5))
                        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
                        .accessibilityLabel("Preview: an email with a safety mark next to each link and a warning at the top")
                        .accessibilityIdentifier("intro-preview")

                    VStack(alignment: .leading, spacing: Spacing.three) {
                        ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                            HStack(alignment: .top, spacing: Spacing.three) {
                                Text("\(index + 1)")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 28, height: 28)
                                    .background(Color.accentColor.gradient, in: Circle())
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: Spacing.half) {
                                    Text(step.title).textStyle(.smallBold)
                                    Text(step.text).textStyle(.small, color: Theme.textSecondary)
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Text("On the next step you'll sign in with your Google account, so Trustmail can read your Gmail.")
                        .textStyle(.small, color: Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("intro-google-note")
                }
                .padding(Spacing.three)
                .frame(maxWidth: Theme.maxContentWidth)
                .frame(maxWidth: .infinity)
            }

            Button(action: onContinue) {
                Text("Continue").font(.headline).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.horizontal, Spacing.three)
            .padding(.bottom, Spacing.two)
            .frame(maxWidth: Theme.maxContentWidth)
            .accessibilityIdentifier("intro-continue")
        }
        .background(Theme.background.ignoresSafeArea())
    }
}
