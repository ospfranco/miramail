import Foundation
import GoogleSignIn
import UIKit

/// Google Sign-In wrapper that also hands out Gmail access tokens.
@MainActor
final class GoogleAuth: AuthService {
    // OAuth client IDs are public identifiers, not secrets.
    // TODO(rename): this client was registered for the OLD bundle ID com.ospfranco.safemail, so Google sign-in will not
    // work until you create an iOS OAuth client for com.ospfranco.trustmail in Google Cloud and put its client ID here,
    // and its reversed form in ios/Info.plist. See RELEASING.md ("Google sign-in after the rename").
    private static let clientID = "760054649215-log9rqaj7h239g6lr9qsink2adnla6i2.apps.googleusercontent.com"

    static let gmailScopes = [
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.send",
    ]

    init() {
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: Self.clientID)
    }

    func restoreSession() async throws -> UserProfile? {
        guard GIDSignIn.sharedInstance.hasPreviousSignIn() else { return nil }
        let user = try await GIDSignIn.sharedInstance.restorePreviousSignIn()
        return profile(of: user)
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
    }

    /// Returns a valid access token, refreshing it if it has expired.
    func accessToken(forceRefresh: Bool) async throws -> String {
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            throw GmailError(status: 401, body: "Not signed in")
        }
        let refreshed = try await user.refreshTokensIfNeeded()
        return refreshed.accessToken.tokenString
    }

    private func profile(of user: GIDGoogleUser) -> UserProfile {
        UserProfile(email: user.profile?.email ?? "")
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
