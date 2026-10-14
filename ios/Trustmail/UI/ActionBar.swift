import SwiftUI

/// The floating bar at the bottom of an email: delete, archive, move and reply.
struct ActionBar: View {
    let isBusy: Bool
    let reply: OutgoingMessage
    let onDelete: () -> Void
    let onArchive: () -> Void
    let onMove: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            button("Delete", systemImage: "trash", id: "action-delete", action: onDelete)
            button("Archive", systemImage: "archivebox", id: "action-archive", action: onArchive)
            button("Move", systemImage: "folder", id: "action-move", action: onMove)
            NavigationLink(value: Route.compose(reply)) {
                label("Reply", systemImage: "arrowshape.turn.up.left")
            }
            .accessibilityIdentifier("action-reply")
        }
        .padding(.horizontal, Spacing.two)
        .padding(.vertical, Spacing.two)
        .glassBackground(in: Capsule())
        .padding(.horizontal, Spacing.three)
        .padding(.bottom, Spacing.two)
        .opacity(isBusy ? 0.5 : 1)
        .disabled(isBusy)
    }

    private func button(_ title: LocalizedStringKey, systemImage: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { label(title, systemImage: systemImage) }
            .accessibilityIdentifier(id)
    }

    private func label(_ title: LocalizedStringKey, systemImage: String) -> some View {
        VStack(spacing: Spacing.half) {
            Image(systemName: systemImage).font(.title3)
            Text(title).font(.caption2.weight(.medium))
        }
        .foregroundStyle(Color.accentColor)
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.one)
        .contentShape(Rectangle())
    }
}

/// A short message that floats over the current screen ("Archived", "Message sent", ...).
struct NoticeBanner: View {
    let text: String

    var body: some View {
        Text(text)
            .textStyle(.smallBold)
            .padding(.horizontal, Spacing.four)
            .padding(.vertical, Spacing.three)
            .glassBackground(in: Capsule())
            .padding(.top, Spacing.two)
            .accessibilityIdentifier("notice")
    }
}
