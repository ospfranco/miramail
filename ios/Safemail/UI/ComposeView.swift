import SwiftUI

/// Write and send an email. Reached from the pencil on the inbox (empty), or from the reply button (with the
/// recipient, subject and thread filled in).
struct ComposeView: View {
    private enum Field { case to, subject, body }

    let session: SessionModel
    private let isReply: Bool
    @State private var model: ComposeModel
    @FocusState private var focus: Field?
    @Environment(\.dismiss) private var dismiss

    init(draft: OutgoingMessage, session: SessionModel) {
        self.session = session
        isReply = draft.inReplyTo != nil
        _model = State(initialValue: ComposeModel(draft: draft, services: session.services))
    }

    var body: some View {
        VStack(spacing: 0) {
            row("To") {
                TextField("", text: $model.to)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .to)
                    .accessibilityIdentifier("compose-to")
            }
            Divider()
            row("Subject") {
                TextField("", text: $model.subject)
                    .focused($focus, equals: .subject)
                    .accessibilityIdentifier("compose-subject")
            }
            Divider()
            TextEditor(text: $model.body)
                .scrollContentBackground(.hidden)
                .focused($focus, equals: .body)
                .padding(.horizontal, Spacing.two)
                .accessibilityIdentifier("compose-body")
            if let error = model.error {
                Text(error).textStyle(.small, color: Verdict.danger.color).padding(Spacing.three)
            }
        }
        .font(.system(size: 16, weight: .medium))
        .foregroundStyle(Theme.text)
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle(isReply ? "Reply" : "New message")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if model.isSending {
                    ProgressView()
                } else {
                    Button("Send") { send() }
                        .disabled(!model.canSend)
                        .accessibilityIdentifier("compose-send")
                }
            }
        }
        .onAppear { focus = model.to.isEmpty ? .to : .body }
    }

    private func row<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: Spacing.two) {
            Text(title).textStyle(.small, color: Theme.textSecondary).frame(width: 60, alignment: .leading)
            content()
        }
        .padding(.horizontal, Spacing.three)
        .padding(.vertical, Spacing.two)
    }

    private func send() {
        Task {
            if await model.send() {
                session.showNotice("Message sent")
                dismiss()
            }
        }
    }
}
