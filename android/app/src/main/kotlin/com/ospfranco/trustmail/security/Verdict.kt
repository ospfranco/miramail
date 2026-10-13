package com.ospfranco.trustmail.security

/** [contentHexColor]: text on a card filled with [hexColor], white except on the light amber of a warning. */
enum class Verdict(val id: String, val glyph: String, val hexColor: String, val contentHexColor: String, private val severity: Int) {
    Safe("safe", "✓", "30A46C", "FFFFFF", 0),
    Warning("warning", "!", "E5A000", "000000", 1),
    Danger("danger", "✕", "E5484D", "FFFFFF", 2);

    companion object {
        fun worst(verdicts: List<Verdict>): Verdict =
            verdicts.fold(Safe) { worst, next -> if (next.severity > worst.severity) next else worst }
    }
}

/** [reason]: why a problem of this kind matters, in plain words, for the warning shown before a risky link opens. */
enum class LinkCheckKind(val label: String, val reason: String) {
    // Local checks on the URL itself
    LinkFormat(
        "Link format",
        "The link is written in an unusual way that real companies rarely use, a common trick to hide what it does or where it goes.",
    ),
    DisplayText(
        "Link text",
        "The link's text shows one website, but it takes you to another. Fake emails do this to look trustworthy.",
    ),
    Encryption(
        "Encryption",
        "The connection to this website isn't secure, so anything you type there, like a password, could be seen by others.",
    ),
    LookalikeDomain(
        "Look-alike domain",
        "The website's name imitates a well-known one with a small change, a trick fake websites use.",
    ),
    Shortener(
        "Link shortener",
        "The link is shortened, so you can't see where it really goes before you open it.",
    ),

    // Network checks
    DnsSpoofing(
        "DNS",
        "Your network sends this link somewhere other than where it should go. Someone may be redirecting you to a fake website.",
    ),
    TlsConnection(
        "TLS connection",
        "Trustmail couldn't make a secure connection to this website, so it can't confirm the website is real.",
    ),
    DomainAge(
        "Domain age",
        "This website was created very recently. Fake websites are often only a few days or weeks old.",
    ),
    SubdomainCertificate(
        "Subdomain certificate",
        "This part of the website was set up very recently, which can mean someone else just added it.",
    ),
    CertificateHostname(
        "Certificate hostname",
        "The website's security certificate is for a different name, so it may not be the website it claims to be.",
    ),
    CertificateIssuer(
        "Certificate issuer",
        "The website's security certificate comes from an unusual provider, which is rare for real companies.",
    ),
}

data class LinkCheck(val kind: LinkCheckKind, val verdict: Verdict, val detail: String)
