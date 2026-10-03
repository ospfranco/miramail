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
    let settings: AppSettings
    @State private var model: MessageModel
    @State private var pendingLink: ScannedLink?
    @State private var browser: BrowserDestination?
    @State private var unopenableLink: String?
    @Environment(\.openURL) private var openURL

    init(id: String, services: Services, settings: AppSettings) {
        self.settings = settings
        _model = State(initialValue: MessageModel(id: id, services: services))
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
            Text(([link.host.isEmpty ? link.url : link.host] + link.checks.filter { $0.verdict != .safe }.map(\.detail)).joined(separator: "\n\n"))
        }
        .alert("Can't open this link", isPresented: Binding(get: { unopenableLink != nil }, set: { if !$0 { unopenableLink = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(unopenableLink ?? "")
        }
    }

    private func content(_ message: MessageDetail) -> some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: Spacing.half) {
                Text(message.subject)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Theme.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text(message.from).textStyle(.small).lineLimit(1)
                Text(message.date).textStyle(.small, color: Theme.textSecondary).lineLimit(1)
                if model.evaluation == nil {
                    Text("Scanning links…").textStyle(.small, color: Theme.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Spacing.three)

            EmailBodyView(
                body: message.body,
                bodyType: message.bodyType,
                scannedLinks: model.links,
                onLinksFound: { found in Task { await model.linksFound(found) } },
                onLinkPress: handleLinkPress,
                allowJavaScript: settings.allowEmailJavaScript
            )
        }
        .overlay {
            if let evaluation = model.evaluation, let links = model.links {
                SecurityPanel(evaluation: evaluation, links: links)
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
