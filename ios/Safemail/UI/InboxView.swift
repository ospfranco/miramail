import SwiftUI

enum Route: Hashable {
    case message(MessageSummary)
    case settings
}

struct InboxView: View {
    let session: SessionModel
    let settings: AppSettings

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                topBar
                if session.user == nil { signedOut } else { signedIn }
            }
            .frame(maxWidth: Theme.maxContentWidth)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background.ignoresSafeArea())
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .message(let summary): MessageView(id: summary.id, services: session.services, settings: settings)
                case .settings: SettingsView(settings: settings, session: session)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    /// The account on the left (when signed in); sign out and the settings button on the right.
    private var topBar: some View {
        HStack(spacing: Spacing.three) {
            if let email = session.user?.email {
                Text(email).textStyle(.smallBold)
                Spacer()
                Button("Sign out") { Task { await session.signOut() } }
                    .textStyle(.link, color: Theme.linkPrimary)
                    .accessibilityIdentifier("sign-out")
            } else {
                Spacer()
            }
            NavigationLink(value: Route.settings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 18))
                    .foregroundStyle(Theme.text)
            }
            .accessibilityLabel("Settings")
            .accessibilityIdentifier("settings-button")
        }
        .padding(.horizontal, Spacing.three)
        .padding(.vertical, Spacing.three)
    }

    private var signedOut: some View {
        VStack(spacing: Spacing.four) {
            Text("safemail").textStyle(.subtitle)
            if session.isLoading {
                ProgressView()
            } else {
                Button { Task { await session.signIn() } } label: {
                    Text("Sign in with Google")
                        .textStyle(.regular)
                        .padding(.horizontal, Spacing.four)
                        .padding(.vertical, Spacing.three)
                        .background(Theme.backgroundElement, in: RoundedRectangle(cornerRadius: Spacing.three))
                }
                .accessibilityIdentifier("sign-in")
            }
            if let error = session.error {
                Text(error).textStyle(.small)
            }
        }
        .padding(.horizontal, Spacing.three)
    }

    private var signedIn: some View {
        VStack(spacing: 0) {
            if let error = session.error {
                Text(error).textStyle(.small).padding(.horizontal, Spacing.three)
            }

            List(session.messages) { message in
                NavigationLink(value: Route.message(message)) {
                    VStack(alignment: .leading, spacing: Spacing.half) {
                        Text(message.from).textStyle(message.unread ? .smallBold : .small).lineLimit(1)
                        Text(message.subject).textStyle(message.unread ? .smallBold : .small).lineLimit(1)
                        Text(message.snippet).textStyle(.small, color: Theme.textSecondary).lineLimit(2)
                    }
                    .padding(.vertical, Spacing.two)
                }
                .listRowBackground(Theme.background)
                .accessibilityIdentifier("message-\(message.id)")
            }
            .listStyle(.plain)
            .refreshable { await session.loadInbox() }
        }
    }
}
