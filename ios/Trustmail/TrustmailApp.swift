import GoogleSignIn
import SwiftUI

@main
struct TrustmailApp: App {
    @State private var session = TrustmailApp.makeSession()
    @State private var settings = TrustmailApp.makeSettings()

    var body: some Scene {
        WindowGroup {
            RootView(session: session, settings: settings)
                .task { await session.start() }
                .onOpenURL { GIDSignIn.sharedInstance.handle($0) }
        }
    }

    @MainActor
    private static func makeSession() -> SessionModel {
        #if DEBUG
        // Demo runs are driven by UI tests, which can be slow to look; keep notices up long enough to be seen.
        if Demo.isEnabled { return SessionModel(services: makeServices(), noticeDuration: .seconds(8)) }
        #endif
        return SessionModel(services: makeServices())
    }

    @MainActor
    private static func makeSettings() -> AppSettings {
        #if DEBUG
        if Demo.isEnabled { return AppSettings(defaults: Demo.freshSettingsStore()) }
        #endif
        return AppSettings()
    }

    @MainActor
    private static func makeServices() -> Services {
        #if DEBUG
        if Demo.isEnabled { return Services(auth: Demo.Auth(), mail: Demo.Mail()) }
        #endif
        let auth = GoogleAuth()
        return Services(auth: auth, mail: GmailClient { forceRefresh in try await auth.accessToken(forceRefresh: forceRefresh) })
    }
}

/// The intro first (once, before anyone has signed in), then the inbox.
struct RootView: View {
    let session: SessionModel
    let settings: AppSettings

    var body: some View {
        Group {
            if session.user == nil && !session.isLoading && !settings.hasSeenIntro {
                IntroView { settings.hasSeenIntro = true }
            } else {
                InboxView(session: session, settings: settings)
            }
        }
        // Someone who is already signed in has no need for the intro, now or after signing out.
        .onChange(of: session.user) { _, user in
            if user != nil { settings.hasSeenIntro = true }
        }
    }
}
