import SwiftUI

struct SettingsView: View {
    @Bindable var settings: AppSettings
    let session: SessionModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section {
                Toggle("Allow JavaScript in emails", isOn: $settings.allowEmailJavaScript)
                    .accessibilityIdentifier("javascript-toggle")
            } footer: {
                Text("Lets scripts inside an email run. Scripts can change what you see after Trustmail has scanned the links, so leave this off unless an email needs it. Applies the next time you open an email.")
            }

            Section {
                Toggle("Ignore footer links", isOn: $settings.ignoreFooterLinks)
                    .accessibilityIdentifier("footer-links-toggle")
            } footer: {
                Text("Suspicious links in an email's footer (unsubscribe, privacy policy, social media) don't count toward the email's verdict. They still get a badge and a warning when you tap them, and an unsafe footer link still counts. Applies the next time you open an email.")
            }

            if let email = session.user?.email {
                Section("Account") {
                    Text(email).foregroundStyle(Theme.textSecondary)
                    Button("Sign out", role: .destructive) {
                        Task {
                            await session.signOut()
                            dismiss()
                        }
                    }
                    .accessibilityIdentifier("settings-sign-out")
                }
            }
        }
        .frame(maxWidth: Theme.maxContentWidth)
        .frame(maxWidth: .infinity)
        .background(Theme.groupedBackground.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}
