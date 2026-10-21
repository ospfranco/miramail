import Foundation
import GoogleSignIn
import UIKit

/// Google Sign-In wrapper that also hands out Gmail access tokens.
@MainActor
final class GoogleAuth: AuthService {
    // OAuth client IDs are public identifiers, not secrets. This iOS client is registered for com.miramail;
    // its reversed form is the URL scheme in ios/Info.plist.
    private static let clientID = "760054649215-log9rqaj7h239g6lr9qsink2adnla6i2.apps.googleusercontent.com"

    static let gmailScopes = [
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.send",
    ]

    /// The signed-in account, remembered so the app can open (and show the saved inbox) when Google can't be reached.
    private static let emailKey = "googleAuth.email"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: Self.clientID)
    }

    func restoreSession() async throws -> UserProfile? {
        guard GIDSignIn.sharedInstance.hasPreviousSignIn() else { return nil }
        do {
            return profile(of: try await GIDSignIn.sharedInstance.restorePreviousSignIn())
        } catch where Self.isNetworkError(error) {
            // Offline: stay signed in. The token is restored on the next request that reaches Google.
            guard let email = defaults.string(forKey: Self.emailKey) else { throw error }
            return UserProfile(email: email)
        }
    }

    /// Whether the error (or one it wraps) is a failure to reach the network, rather than a sign-in that is no longer valid.
    static func isNetworkError(_ error: Error) -> Bool {
        var current: NSError? = error as NSError
        while let nsError = current {
            if nsError.domain == NSURLErrorDomain { return true }
            // AppAuth, which Google Sign-In uses for tokens: OIDErrorCodeNetworkError.
            if nsError.domain == "org.openid.appauth.general" && nsError.code == -5 { return true }
            current = nsError.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }

    func signIn() async throws -> UserProfile? {
        guard let presenter = Self.topViewController() else { return nil }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(
                withPresenting: presenter,
                hint: nil,
                additionalScopes: Self.gmailScopes
            )
            return profile(of: result.user)
        } catch let error as NSError where error.domain == kGIDSignInErrorDomain && error.code == GIDSignInError.canceled.rawValue {
            return nil
        }
    }

    func signOut() async {
        GIDSignIn.sharedInstance.signOut()
        defaults.removeObject(forKey: Self.emailKey)
    }

    /// Returns a valid access token, refreshing it if it has expired.
    func accessToken(forceRefresh: Bool) async throws -> String {
        // After an offline launch the session wasn't restored yet; do it now that Google may be reachable.
        if GIDSignIn.sharedInstance.currentUser == nil && GIDSignIn.sharedInstance.hasPreviousSignIn() {
            _ = try await GIDSignIn.sharedInstance.restorePreviousSignIn()
        }
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            throw GmailError(status: 401, body: "Not signed in")
        }
        let refreshed = try await user.refreshTokensIfNeeded()
        return refreshed.accessToken.tokenString
    }

    private func profile(of user: GIDGoogleUser) -> UserProfile {
        let email = user.profile?.email ?? ""
        defaults.set(email, forKey: Self.emailKey)
        return UserProfile(email: email)
    }

    private static func topViewController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
