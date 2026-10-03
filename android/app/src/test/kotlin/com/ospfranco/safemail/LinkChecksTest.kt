package com.ospfranco.safemail

import com.ospfranco.safemail.security.CertificateHistory
import com.ospfranco.safemail.security.LinkCheck
import com.ospfranco.safemail.security.LinkCheckKind
import com.ospfranco.safemail.security.LinkChecks
import com.ospfranco.safemail.security.PublicDnsAnswer
import com.ospfranco.safemail.security.TlsProbeResult
import com.ospfranco.safemail.security.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LinkChecksTest {
    private val now = date("2026-10-02T12:00:00Z")

    private fun ago(days: Long): Instant = now.minusSeconds(days * 86_400)

    @Test
    fun normalizesIpAddresses() {
        assertEquals("93.184.216.34", LinkChecks.normalizeIp("93.184.216.34"))
        assertEquals("93.184.216.34", LinkChecks.normalizeIp("::ffff:93.184.216.34"))
        assertEquals("2606:2800:220:1:0:0:0:1", LinkChecks.normalizeIp("2606:2800:220:1::1"))
        assertEquals("2606:2800:220:1:0:0:0:1", LinkChecks.normalizeIp("2606:2800:0220:0001:0:0:0:1"))
        assertEquals("0:0:0:0:0:0:0:1", LinkChecks.normalizeIp("::1"))
        assertEquals("0:0:0:0:0:0:0:0", LinkChecks.normalizeIp("::"))
        assertEquals("1:0:0:0:0:0:0:0", LinkChecks.normalizeIp("1::"))
        assertEquals("fe80:0:0:0:0:0:0:1", LinkChecks.normalizeIp("FE80::1%en0"))
    }

    @Test
    fun recognizesReservedAddresses() {
        for (ip in listOf("0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.1", "192.168.1.1", "100.64.0.1", "224.0.0.1", "0:0:0:0:0:0:0:1", "fd00:0:0:0:0:0:0:1", "fe80:0:0:0:0:0:0:1")) {
            assertTrue(ip, LinkChecks.isReservedIp(ip))
        }
        for (ip in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "93.184.216.34", "2606:2800:220:1:0:0:0:1")) {
            assertFalse(ip, LinkChecks.isReservedIp(ip))
        }
    }

    private val trusted = TlsProbeResult.Ok(true, null, makeCertificate())
    private val untrusted = TlsProbeResult.Ok(false, "bad", makeCertificate())

    private fun dns(
        device: List<String>,
        publicIps: List<String>,
        exists: Boolean = true,
        tls: TlsProbeResult? = null,
        reachable: Boolean = true,
    ) = LinkChecks.checkDns("example.com", device, if (reachable) PublicDnsAnswer(exists, publicIps) else null, tls)

    @Test
    fun dnsAgreement() {
        assertEquals(
            LinkCheck(LinkCheckKind.DnsSpoofing, Verdict.Safe, "Your network and public DNS agree on the address."),
            dns(listOf("1.2.3.4"), listOf("1.2.3.4", "5.6.7.8")),
        )
        // Same IPv6 address written differently still agrees.
        assertEquals(Verdict.Safe, dns(listOf("2606:2800:220:1:0:0:0:1"), listOf("2606:2800:220:1::1")).verdict)
    }

    @Test
    fun dnsPublicResolverUnreachable() {
        assertEquals(
            LinkCheck(LinkCheckKind.DnsSpoofing, Verdict.Warning, "Couldn't reach public DNS to compare answers."),
            dns(listOf("1.2.3.4"), emptyList(), reachable = false),
        )
    }

    @Test
    fun dnsNoAddressesAnywhere() {
        assertEquals(
            LinkCheck(LinkCheckKind.DnsSpoofing, Verdict.Warning, "example.com doesn't exist or has no addresses."),
            dns(emptyList(), emptyList(), exists = false),
        )
    }

    @Test
    fun dnsDomainDoesNotExistPublicly() {
        val result = dns(listOf("1.2.3.4"), emptyList(), exists = false)
        assertEquals(Verdict.Danger, result.verdict)
        assertEquals("Your network resolves example.com, but public DNS says the domain doesn't exist.", result.detail)
    }

    @Test
    fun dnsPointsToPrivateAddress() {
        val result = dns(listOf("192.168.1.9"), listOf("1.2.3.4"))
        assertEquals(Verdict.Danger, result.verdict)
        assertEquals("Your network points example.com to a private address (192.168.1.9) instead of its public one.", result.detail)
    }

    @Test
    fun dnsMismatchIsFineWhenTheCertificateIsTrusted() {
        val result = dns(listOf("9.9.9.9"), listOf("1.2.3.4"), tls = trusted)
        assertEquals(Verdict.Safe, result.verdict)
        assertEquals("DNS answers differ (normal for CDNs), but the server proved its identity with a valid certificate.", result.detail)
    }

    @Test
    fun dnsMismatchWithoutTrustedCertificate() {
        assertEquals(Verdict.Danger, dns(listOf("9.9.9.9"), listOf("1.2.3.4"), tls = untrusted).verdict)
        assertEquals(Verdict.Danger, dns(listOf("9.9.9.9"), listOf("1.2.3.4"), tls = TlsProbeResult.Failure("timeout")).verdict)
        val unencrypted = dns(listOf("9.9.9.9"), listOf("1.2.3.4"), tls = null)
        assertEquals(Verdict.Warning, unencrypted.verdict)
        assertEquals("Your network and public DNS disagree on this domain's address, and the link isn't encrypted to confirm it.", unencrypted.detail)
    }

    @Test
    fun formatsAges() {
        assertEquals("1 hours", LinkChecks.formatAge(0.01))
        assertEquals("12 hours", LinkChecks.formatAge(0.5))
        assertEquals("1 days", LinkChecks.formatAge(1.0))
        assertEquals("41 days", LinkChecks.formatAge(41.9))
    }

    @Test
    fun domainAge() {
        fun age(history: CertificateHistory?, encrypted: Boolean = true) = LinkChecks.checkDomainAge("a.com", history, encrypted, now)

        assertEquals(LinkCheck(LinkCheckKind.DomainAge, Verdict.Safe, "a.com has had certificates for at least 400 days."), age(CertificateHistory(ago(400))))
        assertEquals(
            LinkCheck(LinkCheckKind.DomainAge, Verdict.Danger, "a.com's first certificate was issued only 3 days ago. New domains are often used for phishing."),
            age(CertificateHistory(ago(3))),
        )
        assertEquals(Verdict.Safe, age(CertificateHistory(ago(15))).verdict)
        assertEquals(LinkCheck(LinkCheckKind.DomainAge, Verdict.Warning, "Couldn't look up a.com's certificate history."), age(null))
        assertEquals(Verdict.Danger, age(CertificateHistory(null)).verdict)
        assertEquals(
            LinkCheck(LinkCheckKind.DomainAge, Verdict.Warning, "a.com has never had an HTTPS certificate, so its age can't be checked."),
            age(CertificateHistory(null), encrypted = false),
        )
    }

    @Test
    fun tlsFailureIsAWarning() {
        assertEquals(
            listOf(LinkCheck(LinkCheckKind.TlsConnection, Verdict.Warning, "Couldn't open a secure connection to check the certificate (timed out).")),
            LinkChecks.checkTls("a.com", "a.com", TlsProbeResult.Failure("timed out"), null, now),
        )
    }

    private fun tls(host: TlsProbeResult, apex: TlsProbeResult? = null) = LinkChecks.checkTls("a.com", "a.com", host, apex, now)

    @Test
    fun tlsHostnameAndIssuer() {
        val good = TlsProbeResult.Ok(true, null, makeCertificate(dnsNames = listOf("a.com")))
        assertEquals(
            listOf(
                LinkCheck(LinkCheckKind.CertificateHostname, Verdict.Safe, "Certificate is valid for a.com."),
                LinkCheck(LinkCheckKind.CertificateIssuer, Verdict.Safe, "Issued by Let's Encrypt."),
            ),
            tls(good),
        )

        val wrong = TlsProbeResult.Ok(true, null, makeCertificate(dnsNames = listOf("x.com", "y.com", "z.com", "w.com")))
        assertEquals(
            LinkCheck(LinkCheckKind.CertificateHostname, Verdict.Danger, "Certificate is for x.com, y.com, z.com, not a.com."),
            tls(wrong)[0],
        )

        val unknownCa = TlsProbeResult.Ok(true, null, makeCertificate(issuerOrg = "Shady CA", dnsNames = listOf("a.com")))
        assertEquals(
            LinkCheck(LinkCheckKind.CertificateIssuer, Verdict.Warning, "Issued by Shady CA, which isn't a common certificate authority."),
            tls(unknownCa)[1],
        )

        val untrustedCert = TlsProbeResult.Ok(false, "expired", makeCertificate(dnsNames = listOf("a.com")))
        assertEquals(
            LinkCheck(LinkCheckKind.CertificateIssuer, Verdict.Danger, "Your device doesn't trust this certificate: expired"),
            tls(untrustedCert)[1],
        )

        val noIssuer = TlsProbeResult.Ok(true, null, makeCertificate(issuerOrg = null, issuerCommonName = null, dnsNames = listOf("a.com")))
        assertEquals("Issued by Unknown issuer, which isn't a common certificate authority.", tls(noIssuer)[1].detail)
    }

    @Test
    fun issuerMatchingIsACaseInsensitivePrefix() {
        val digicert = TlsProbeResult.Ok(true, null, makeCertificate(issuerOrg = "DigiCert Inc", dnsNames = listOf("a.com")))
        assertEquals(Verdict.Safe, tls(digicert)[1].verdict)
        val lower = TlsProbeResult.Ok(true, null, makeCertificate(issuerOrg = "google trust services llc", dnsNames = listOf("a.com")))
        assertEquals(Verdict.Safe, tls(lower)[1].verdict)
    }

    @Test
    fun subdomainCertificates() {
        fun probe(der: String, issued: Instant) =
            TlsProbeResult.Ok(true, null, makeCertificate(der = der, notBefore = issued, dnsNames = listOf("login.a.com", "a.com")))

        fun subdomainCheck(host: TlsProbeResult, apex: TlsProbeResult?) =
            LinkChecks.checkTls("login.a.com", "a.com", host, apex, now).firstOrNull { it.kind == LinkCheckKind.SubdomainCertificate }

        assertEquals(
            LinkCheck(LinkCheckKind.SubdomainCertificate, Verdict.Safe, "login.a.com uses the same certificate as a.com."),
            subdomainCheck(probe("SAME", now), probe("SAME", now)),
        )

        val fresh = subdomainCheck(probe("H", now.minusSeconds(3_600)), probe("A", ago(90)))
        assertEquals(Verdict.Danger, fresh?.verdict)
        assertEquals("login.a.com got its own certificate only 1 hours ago, on an otherwise established domain.", fresh?.detail)

        assertEquals(
            LinkCheck(LinkCheckKind.SubdomainCertificate, Verdict.Safe, "login.a.com has its own certificate, issued 30 days ago."),
            subdomainCheck(probe("H", ago(30)), probe("A", ago(90))),
        )

        // Nothing to compare against when the apex can't be probed, or for the apex itself.
        assertNull(subdomainCheck(probe("H", now), null))
        assertNull(subdomainCheck(probe("H", now), TlsProbeResult.Failure("x")))
        assertEquals(2, LinkChecks.checkTls("a.com", "a.com", probe("H", now), probe("A", now), now).size)
    }
}
