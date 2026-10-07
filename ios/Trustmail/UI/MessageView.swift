import SafariServices
import SwiftUI

private struct BrowserDestination: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController { SFSafariViewController(url: url) }
    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

struct MessageView: View {
    let session: SessionModel
    let settings: AppSettings
    @State private var model: MessageModel
    @State private var moveTargets: [MailLabel] = []
    @State private var showMoveChooser = false
    @State private var pendingLink: ScannedLink?
    @State private var browser: BrowserDestination?
    @State private var unopenableLink: String?
    /// The email body is scrolled down, so the header is compact.
    @State private var headerCollapsed = false
    @Environment(\.dismiss) private var dismiss

    init(id: String, session: SessionModel, settings: AppSettings) {
        self.session = session
        self.settings = settings
        _model = State(initialValue: MessageModel(id: id, services: session.services))
    }

    var body: some View {
        Group {
            if let error = model.error {
                Text(error).textStyle(.small).padding(Spacing.four).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let message = model.message {
                content(message)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
        .fullScreenCover(item: $browser) { SafariView(url: $0.url).ignoresSafeArea() }
        .alert(
            pendingLink?.verdict == .danger ? "This link looks unsafe" : "Be careful with this link",
            isPresented: Binding(get: { pendingLink != nil }, set: { if !$0 { pendingLink = nil } }),
            presenting: pendingLink
        ) { link in
            Button("Cancel", role: .cancel) {}
            Button("Open anyway", role: .destructive) { open(link.url) }
        } message: { link in
            Text(SecurityScanner.linkWarning(link))
        }
        .confirmationDialog("Move to", isPresented: $showMoveChooser, titleVisibility: .visible) {
            ForEach(moveTargets) { label in
                Button(label.name) { perform(.move(label)) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Can't open this link", isPresented: Binding(get: { unopenableLink != nil }, set: { if !$0 { unopenableLink = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(unopenableLink ?? "")
        }
    }

    private func content(_ message: MessageDetail) -> some View {
        VStack(spacing: 0) {
            MessageHeader(message: message, evaluation: model.evaluation, collapsed: headerCollapsed)

            Divider()

            EmailBodyView(
                body: message.body,
                bodyType: message.bodyType,
                scannedLinks: model.links,
                onLinksFound: { found in Task { await model.linksFound(found, ignoreFooterLinks: settings.ignoreFooterLinks) } },
                onLinkPress: handleLinkPress,
                allowJavaScript: settings.allowEmailJavaScript,
                onScroll: { offset, scrollable in
                    let collapsed = HeaderCollapse.collapsed(current: headerCollapsed, offset: offset, scrollable: scrollable)
                    // Changed outside the view update the scroll may have come from.
                    if collapsed != headerCollapsed { Task { headerCollapsed = collapsed } }
                }
            )
        }
        .safeAreaInset(edge: .bottom) {
            if let reply = model.reply {
                ActionBar(
                    isBusy: model.isBusy,
                    reply: reply,
                    onDelete: { perform(.trash) },
                    onArchive: { perform(.archive) },
                    onMove: chooseMoveTarget
                )
            }
        }
        .overlay {
            // The tab is there from the start, with a spinner until the scan is done.
            SecurityPanel(evaluation: model.evaluation, links: model.links ?? [])
        }
    }

    /// Runs an action and, when it worked, leaves the email: it is no longer in the inbox.
    private func perform(_ action: MessageAction) {
        Task {
            if await model.perform(action) {
                session.removeMessage(model.id, notice: action.notice)
                dismiss()
            } else {
                session.showNotice(action.failureNotice)
            }
        }
    }

    private func chooseMoveTarget() {
        Task {
            do {
                moveTargets = try await model.loadMoveTargets()
                showMoveChooser = true
            } catch {
                session.showNotice("Couldn't load your labels")
            }
        }
    }

    private func open(_ urlString: String) {
        guard let url = URL(string: urlString) else {
            unopenableLink = urlString
            return
        }
        if ["http", "https"].contains(url.scheme?.lowercased()) {
            browser = BrowserDestination(url: url)
        } else {
            UIApplication.shared.open(url) { opened in
                if !opened { unopenableLink = urlString }
            }
        }
    }

    private func handleLinkPress(_ url: String) {
        guard let link = model.links?.first(where: { $0.url == url }), link.verdict != .safe else { return open(url) }
        pendingLink = link
    }
}
