import Foundation

enum Verdict: String, Sendable, CaseIterable {
    case safe
    case warning
    case danger

    var glyph: String {
        switch self {
        case .safe: "✓"
        case .warning: "!"
        case .danger: "✕"
        }
    }

    /// Hex color without the leading "#", shared by the native UI and the badges injected into the email.
    var hexColor: String {
        switch self {
        case .safe: "30A46C"
        case .warning: "E5A000"
        case .danger: "E5484D"
        }
    }

    /// Text on a card filled with `hexColor`: white, except on the light amber of a warning.
    var contentHexColor: String {
        switch self {
        case .safe, .danger: "FFFFFF"
        case .warning: "000000"
        }
    }

    private var severity: Int {
        switch self {
        case .safe: 0
        case .warning: 1
        case .danger: 2
        }
    }

    static func worst(of verdicts: [Verdict]) -> Verdict {
        verdicts.reduce(.safe) { $1.severity > $0.severity ? $1 : $0 }
    }
}

enum LinkCheckKind: String, Sendable, CaseIterable {
    // Local checks on the URL itself
    case linkFormat = "link-format"
    case displayText = "display-text"
    case encryption
    case lookalikeDomain = "lookalike-domain"
    case shortener
    // Network checks
    case dnsSpoofing = "dns-spoofing"
    case tlsConnection = "tls-connection"
    case domainAge = "domain-age"
    case subdomainCertificate = "subdomain-certificate"
    case certificateHostname = "certificate-hostname"
    case certificateIssuer = "certificate-issuer"

    var label: String {
        switch self {
        case .linkFormat: "Link format"
        case .displayText: "Link text"
        case .encryption: "Encryption"
        case .lookalikeDomain: "Look-alike domain"
        case .shortener: "Link shortener"
        case .dnsSpoofing: "DNS"
        case .tlsConnection: "TLS connection"
        case .domainAge: "Domain age"
        case .subdomainCertificate: "Subdomain certificate"
        case .certificateHostname: "Certificate hostname"
        case .certificateIssuer: "Certificate issuer"
        }
    }

    /// Why a problem of this kind matters, in plain words, for the warning shown before a risky link opens.
    var reason: String {
        switch self {
        case .linkFormat:
            "The link is written in an unusual way that real companies rarely use, a common trick to hide what it does or where it goes."
        case .displayText:
            "The link's text shows one website, but it takes you to another. Fake emails do this to look trustworthy."
        case .encryption:
            "The connection to this website isn't secure, so anything you type there, like a password, could be seen by others."
        case .lookalikeDomain:
            "The website's name imitates a well-known one with a small change, a trick fake websites use."
        case .shortener:
            "The link is shortened, so you can't see where it really goes before you open it."
        case .dnsSpoofing:
            "Your network sends this link somewhere other than where it should go. Someone may be redirecting you to a fake website."
        case .tlsConnection:
            "Trustmail couldn't make a secure connection to this website, so it can't confirm the website is real."
        case .domainAge:
            "This website was created very recently. Fake websites are often only a few days or weeks old."
        case .subdomainCertificate:
            "This part of the website was set up very recently, which can mean someone else just added it."
        case .certificateHostname:
            "The website's security certificate is for a different name, so it may not be the website it claims to be."
        case .certificateIssuer:
            "The website's security certificate comes from an unusual provider, which is rare for real companies."
        }
    }
}

struct LinkCheck: Sendable, Equatable {
    let kind: LinkCheckKind
    let verdict: Verdict
    let detail: String
}
