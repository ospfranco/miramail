import SwiftUI

enum Route: Hashable {
    case message(MessageSummary)
    case settings
    case compose(OutgoingMessage)
}

struct InboxView: View {
    let session: SessionModel
    let settings: AppSettings

    var body: some View {
        NavigationStack {
            Group {
                if session.user == nil { signedOut } else { signedIn }
            }
            .frame(maxWidth: Theme.maxContentWidth)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle(session.user == nil ? Text(verbatim: "") : Text("Inbox"))
            .toolbar { toolbar }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .message(let summary): MessageView(id: summary.id, session: session, settings: settings)
                case .settings: SettingsView(settings: settings, session: session)
                case .compose(let draft): ComposeView(draft: draft, session: session)
                }
            }
        }
        .overlay(alignment: .top) {
            if let notice = session.notice {
                NoticeBanner(text: notice).transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: session.notice)
    }

    /// Settings at the top; like Mail, the account and the pencil for a new email sit in the bottom bar (when signed in).
    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            NavigationLink(value: Route.settings) {
                Image(systemName: "gearshape")
            }
            .accessibilityLabel("Settings")
            .accessibilityIdentifier("settings-button")
        }
        if let email = session.user?.email {
            ToolbarItem(placement: .status) {
                Text(email).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(1)
            }
            ToolbarItemGroup(placement: .bottomBar) {
                Spacer()
                NavigationLink(value: Route.compose(OutgoingMessage(to: "", subject: "", body: ""))) {
                    Image(systemName: "square.and.pencil")
                }
                .accessibilityLabel("New message")
                .accessibilityIdentifier("new-message")
            }
        }
    }

    private var signedOut: some View {
        VStack(spacing: Spacing.four) {
            Image(systemName: "envelope.badge.shield.half.filled")
                .font(.system(size: 64))
                .foregroundStyle(Theme.brand.gradient)
                .accessibilityHidden(true)
            Text(verbatim: "Trustmail").textStyle(.subtitle)
            if session.isLoading {
                ProgressView()
            } else {
                Button { Task { await session.signIn() } } label: {
                    Text("Sign in with Google").font(.headline).padding(.horizontal, Spacing.three)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("sign-in")
            }
            if let error = session.error {
                Text(error).textStyle(.small, color: Theme.textSecondary).multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, Spacing.three)
    }

    private var signedIn: some View {
        List {
            // With the saved copy on screen, the note says what happened; the raw error would only repeat it.
            if session.showsSavedInbox {
                Label("Couldn't refresh your inbox. Showing the copy saved on this device.", systemImage: "icloud.slash")
                    .textStyle(.small, color: Theme.textSecondary)
                    .accessibilityIdentifier("saved-inbox")
            } else if let error = session.error {
                Text(error).textStyle(.small, color: Verdict.danger.color)
            }
            ForEach(session.messages) { message in
                NavigationLink(value: Route.message(message)) {
                    MessageRow(message: message, isFirstMessage: session.firstMessages.contains(message.id))
                }
                .accessibilityIdentifier("message-\(message.id)")
            }
        }
        .listStyle(.plain)
        .refreshable { await session.loadInbox() }
    }
}

/// One email in the inbox, laid out like Mail: unread dot, avatar, sender, subject and a two-line preview.
/// Under the sender: the domain in red when the sender failed Gmail's checks, and a yellow marker when they are new to you.
private struct MessageRow: View {
    let message: MessageSummary
    /// You've never exchanged email with the sender.
    let isFirstMessage: Bool

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.three) {
            Circle()
                .fill(message.unread ? Color.accentColor : .clear)
                .frame(width: 10, height: 10)
                .padding(.top, 15)
                .padding(.trailing, -Spacing.two)
                .accessibilityHidden(true)
            AvatarView(from: message.from)
            VStack(alignment: .leading, spacing: Spacing.half) {
                Text(senderName(message.from))
                    .font(.headline.weight(message.unread ? .semibold : .regular))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                if message.senderFailed {
                    FailedSenderLine(address: bareAddress(message.from))
                }
                if isFirstMessage {
                    FirstMessageLine()
                }
                Text(displaySubject(message.subject))
                    .font(.subheadline.weight(message.unread ? .semibold : .regular))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                Text(message.snippet)
                    .font(.subheadline)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, Spacing.one)
    }
}
