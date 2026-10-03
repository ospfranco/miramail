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
}

struct LinkCheck: Sendable, Equatable {
    let kind: LinkCheckKind
    let verdict: Verdict
    let detail: String
}
