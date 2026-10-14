package com.ospfranco.trustmail.security

import androidx.annotation.StringRes
import com.ospfranco.trustmail.R

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

/**
 * [reason]: why a problem of this kind matters, in plain words, for the warning shown before a risky link opens.
 * [label] is technical and only used in tests and debugging, so it isn't translated.
 */
enum class LinkCheckKind(val label: String, @StringRes val reason: Int) {
    // Local checks on the URL itself
    LinkFormat("Link format", R.string.reason_link_format),
    DisplayText("Link text", R.string.reason_display_text),
    Encryption("Encryption", R.string.reason_encryption),
    LookalikeDomain("Look-alike domain", R.string.reason_lookalike_domain),
    Shortener("Link shortener", R.string.reason_shortener),

    // Network checks
    DnsSpoofing("DNS", R.string.reason_dns_spoofing),
    TlsConnection("TLS connection", R.string.reason_tls_connection),
    DomainAge("Domain age", R.string.reason_domain_age),
    SubdomainCertificate("Subdomain certificate", R.string.reason_subdomain_certificate),
    CertificateHostname("Certificate hostname", R.string.reason_certificate_hostname),
    CertificateIssuer("Certificate issuer", R.string.reason_certificate_issuer),
}

/** [detail] is technical, for tests and debugging; it isn't shown, so it isn't translated. */
data class LinkCheck(val kind: LinkCheckKind, val verdict: Verdict, val detail: String)
