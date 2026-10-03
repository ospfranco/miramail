import Foundation
import GoogleSignIn
import UIKit

/// Google Sign-In wrapper that also hands out Gmail access tokens.
@MainActor
final class GoogleAuth: AuthService {
    // OAuth client IDs are public identifiers, not secrets.
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
