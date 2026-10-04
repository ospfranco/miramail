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
        .background(Theme.backgroundElement, in: Capsule())
        .overlay(Capsule().stroke(Theme.backgroundSelected, lineWidth: 1))
        .shadow(color: .black.opacity(0.15), radius: 10, y: 3)
        .padding(.horizontal, Spacing.three)
        .padding(.bottom, Spacing.two)
        .opacity(isBusy ? 0.5 : 1)
        .disabled(isBusy)
    }

    private func button(_ title: String, systemImage: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { label(title, systemImage: systemImage) }
            .accessibilityIdentifier(id)
    }

    private func label(_ title: String, systemImage: String) -> some View {
        VStack(spacing: Spacing.half) {
            Image(systemName: systemImage).font(.system(size: 20))
            Text(title).font(.system(size: 11, weight: .medium))
        }
        .foregroundStyle(Theme.text)
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
            .textStyle(.small, color: Theme.background)
            .padding(.horizontal, Spacing.three)
            .padding(.vertical, Spacing.two)
            .background(Theme.text, in: Capsule())
            .shadow(color: .black.opacity(0.2), radius: 6, y: 2)
            .padding(.top, Spacing.two)
            .accessibilityIdentifier("notice")
    }
}
