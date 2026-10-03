import SwiftUI

struct SettingsView: View {
    @Bindable var settings: AppSettings
    let session: SessionModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: Spacing.two) {
                card {
                    Toggle(isOn: $settings.allowEmailJavaScript) {
                        Text("Allow JavaScript in emails").textStyle(.regular)
                    }
                    .accessibilityIdentifier("javascript-toggle")

                    Text("Lets scripts inside an email run. Scripts can change what you see after Safemail has scanned the links, so leave this off unless an email needs it. Applies the next time you open an email.")
                        .textStyle(.small, color: Theme.textSecondary)
                }

                if let email = session.user?.email {
                    card {
                        Text(email).textStyle(.small, color: Theme.textSecondary)
                        Button {
                            Task {
                                await session.signOut()
                                dismiss()
                            }
                        } label: {
                            Text("Sign out").textStyle(.regular, color: Verdict.danger.color)
                        }
                        .accessibilityIdentifier("settings-sign-out")
                    }
                }
            }
            .padding(Spacing.three)
            .frame(maxWidth: Theme.maxContentWidth)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Spacing.two, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Spacing.three)
            .background(Theme.backgroundElement, in: RoundedRectangle(cornerRadius: Spacing.three))
    }
}
