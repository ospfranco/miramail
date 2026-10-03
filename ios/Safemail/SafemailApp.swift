import GoogleSignIn
import SwiftUI

@main
struct SafemailApp: App {
    @State private var session = SessionModel(services: SafemailApp.makeServices())
    @State private var settings = SafemailApp.makeSettings()

    var body: some Scene {
        WindowGroup {
            InboxView(session: session, settings: settings)
                .task { await session.start() }
                .onOpenURL { GIDSignIn.sharedInstance.handle($0) }
        }
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
