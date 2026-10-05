package com.ospfranco.trustmail.security

enum class Verdict(val id: String, val glyph: String, val hexColor: String, private val severity: Int) {
    Safe("safe", "✓", "30A46C", 0),
    Warning("warning", "!", "E5A000", 1),
    Danger("danger", "✕", "E5484D", 2);

    companion object {
        fun worst(verdicts: List<Verdict>): Verdict =
            verdicts.fold(Safe) { worst, next -> if (next.severity > worst.severity) next else worst }
    }
}

enum class LinkCheckKind(val label: String) {
    // Local checks on the URL itself
    LinkFormat("Link format"),
    DisplayText("Link text"),
    Encryption("Encryption"),
    LookalikeDomain("Look-alike domain"),
    Shortener("Link shortener"),

    // Network checks
    DnsSpoofing("DNS"),
    TlsConnection("TLS connection"),
    DomainAge("Domain age"),
    SubdomainCertificate("Subdomain certificate"),
    CertificateHostname("Certificate hostname"),
    CertificateIssuer("Certificate issuer"),
}

data class LinkCheck(val kind: LinkCheckKind, val verdict: Verdict, val detail: String)
