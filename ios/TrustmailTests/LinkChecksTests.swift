import Foundation
import Testing
@testable import Trustmail

struct LinkChecksTests {
    private let now = date("2026-10-02T12:00:00Z")

    @Test func normalizesIpAddresses() {
        #expect(LinkChecks.normalizeIp("93.184.216.34") == "93.184.216.34")
        #expect(LinkChecks.normalizeIp("::ffff:93.184.216.34") == "93.184.216.34")
        #expect(LinkChecks.normalizeIp("2606:2800:220:1::1") == "2606:2800:220:1:0:0:0:1")
        #expect(LinkChecks.normalizeIp("2606:2800:0220:0001:0:0:0:1") == "2606:2800:220:1:0:0:0:1")
        #expect(LinkChecks.normalizeIp("::1") == "0:0:0:0:0:0:0:1")
        #expect(LinkChecks.normalizeIp("::") == "0:0:0:0:0:0:0:0")
        #expect(LinkChecks.normalizeIp("1::") == "1:0:0:0:0:0:0:0")
        #expect(LinkChecks.normalizeIp("FE80::1%en0") == "fe80:0:0:0:0:0:0:1")
    }

    @Test func recognizesReservedAddresses() {
        for ip in ["0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.1", "192.168.1.1", "100.64.0.1", "224.0.0.1", "0:0:0:0:0:0:0:1", "fd00:0:0:0:0:0:0:1", "fe80:0:0:0:0:0:0:1"] {
            #expect(LinkChecks.isReservedIp(ip), "\(ip)")
        }
        for ip in ["8.8.8.8", "172.32.0.1", "172.15.0.1", "100.128.0.1", "93.184.216.34", "2606:2800:220:1:0:0:0:1"] {
            #expect(!LinkChecks.isReservedIp(ip), "\(ip)")
        }
    }

    private let trusted = TlsProbeResult.ok(trusted: true, trustError: nil, leaf: makeCertificate())
    private let untrusted = TlsProbeResult.ok(trusted: false, trustError: "bad", leaf: makeCertificate())

    private func dns(device: [String], publicIps: [String], exists: Bool = true, tls: TlsProbeResult? = nil, reachable: Bool = true) -> LinkCheck {
        LinkChecks.checkDns(
            host: "example.com",
            deviceAddresses: device,
            publicDns: reachable ? PublicDnsAnswer(exists: exists, addresses: publicIps) : nil,
            tls: tls
        )
    }

    @Test func dnsAgreement() {
        let result = dns(device: ["1.2.3.4"], publicIps: ["1.2.3.4", "5.6.7.8"])
        #expect(result == LinkCheck(kind: .dnsSpoofing, verdict: .safe, detail: "Your network and public DNS agree on the address."))
        // Same IPv6 address written differently still agrees.
        #expect(dns(device: ["2606:2800:220:1:0:0:0:1"], publicIps: ["2606:2800:220:1::1"]).verdict == .safe)
    }

    @Test func dnsPublicResolverUnreachable() {
        let result = dns(device: ["1.2.3.4"], publicIps: [], reachable: false)
        #expect(result == LinkCheck(kind: .dnsSpoofing, verdict: .warning, detail: "Couldn't reach public DNS to compare answers."))
    }

    @Test func dnsNoAddressesAnywhere() {
        let result = dns(device: [], publicIps: [], exists: false)
        #expect(result == LinkCheck(kind: .dnsSpoofing, verdict: .warning, detail: "example.com doesn't exist or has no addresses."))
    }

    @Test func dnsDomainDoesNotExistPublicly() {
        let result = dns(device: ["1.2.3.4"], publicIps: [], exists: false)
        #expect(result.verdict == .danger)
        #expect(result.detail == "Your network resolves example.com, but public DNS says the domain doesn't exist.")
    }

    @Test func dnsPointsToPrivateAddress() {
        let result = dns(device: ["192.168.1.9"], publicIps: ["1.2.3.4"])
        #expect(result.verdict == .danger)
        #expect(result.detail == "Your network points example.com to a private address (192.168.1.9) instead of its public one.")
    }

    @Test func dnsMismatchIsFineWhenTheCertificateIsTrusted() {
        let result = dns(device: ["9.9.9.9"], publicIps: ["1.2.3.4"], tls: trusted)
        #expect(result.verdict == .safe)
        #expect(result.detail == "DNS answers differ (normal for CDNs), but the server proved its identity with a valid certificate.")
    }

    @Test func dnsMismatchWithoutTrustedCertificate() {
        #expect(dns(device: ["9.9.9.9"], publicIps: ["1.2.3.4"], tls: untrusted).verdict == .danger)
        #expect(dns(device: ["9.9.9.9"], publicIps: ["1.2.3.4"], tls: .failure("timeout")).verdict == .danger)
        let unencrypted = dns(device: ["9.9.9.9"], publicIps: ["1.2.3.4"], tls: nil)
        #expect(unencrypted.verdict == .warning)
        #expect(unencrypted.detail == "Your network and public DNS disagree on this domain's address, and the link isn't encrypted to confirm it.")
    }

    @Test func formatsAges() {
        #expect(LinkChecks.formatAge(0.01) == "1 hours")
        #expect(LinkChecks.formatAge(0.5) == "12 hours")
        #expect(LinkChecks.formatAge(1) == "1 days")
        #expect(LinkChecks.formatAge(41.9) == "41 days")
    }

    @Test func domainAge() {
        let old = CertificateHistory(firstIssued: now.addingTimeInterval(-400 * 86_400))
        let fresh = CertificateHistory(firstIssued: now.addingTimeInterval(-3 * 86_400))
        let boundary = CertificateHistory(firstIssued: now.addingTimeInterval(-15 * 86_400))

        #expect(LinkChecks.checkDomainAge(domain: "a.com", history: old, encrypted: true, now: now)
            == LinkCheck(kind: .domainAge, verdict: .safe, detail: "a.com has had certificates for at least 400 days."))
        #expect(LinkChecks.checkDomainAge(domain: "a.com", history: fresh, encrypted: true, now: now)
            == LinkCheck(kind: .domainAge, verdict: .danger, detail: "a.com's first certificate was issued only 3 days ago. New domains are often used for phishing."))
        #expect(LinkChecks.checkDomainAge(domain: "a.com", history: boundary, encrypted: true, now: now).verdict == .safe)
        #expect(LinkChecks.checkDomainAge(domain: "a.com", history: nil, encrypted: true, now: now)
            == LinkCheck(kind: .domainAge, verdict: .warning, detail: "Couldn't look up a.com's certificate history."))
        #expect(LinkChecks.checkDomainAge(domain: "a.com", history: CertificateHistory(firstIssued: nil), encrypted: true, now: now).verdict == .danger)
        #expect(LinkChecks.checkDomainAge(domain: "a.com", history: CertificateHistory(firstIssued: nil), encrypted: false, now: now)
            == LinkCheck(kind: .domainAge, verdict: .warning, detail: "a.com has never had an HTTPS certificate, so its age can't be checked."))
    }

    @Test func tlsFailureIsAWarning() {
        let checks = LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: .failure("timed out"), apexTls: nil, now: now)
        #expect(checks == [LinkCheck(kind: .tlsConnection, verdict: .warning, detail: "Couldn't open a secure connection to check the certificate (timed out).")])
    }

    @Test func tlsHostnameAndIssuer() {
        let good = TlsProbeResult.ok(trusted: true, trustError: nil, leaf: makeCertificate(dnsNames: ["a.com"]))
        let checks = LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: good, apexTls: nil, now: now)
        #expect(checks == [
            LinkCheck(kind: .certificateHostname, verdict: .safe, detail: "Certificate is valid for a.com."),
            LinkCheck(kind: .certificateIssuer, verdict: .safe, detail: "Issued by Let's Encrypt."),
        ])

        let wrong = TlsProbeResult.ok(trusted: true, trustError: nil, leaf: makeCertificate(dnsNames: ["x.com", "y.com", "z.com", "w.com"]))
        #expect(LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: wrong, apexTls: nil, now: now)[0]
            == LinkCheck(kind: .certificateHostname, verdict: .danger, detail: "Certificate is for x.com, y.com, z.com, not a.com."))

        let unknownCa = TlsProbeResult.ok(trusted: true, trustError: nil, leaf: makeCertificate(issuerOrg: "Shady CA", dnsNames: ["a.com"]))
        #expect(LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: unknownCa, apexTls: nil, now: now)[1]
            == LinkCheck(kind: .certificateIssuer, verdict: .warning, detail: "Issued by Shady CA, which isn't a common certificate authority."))

        let untrustedCert = TlsProbeResult.ok(trusted: false, trustError: "expired", leaf: makeCertificate(dnsNames: ["a.com"]))
        #expect(LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: untrustedCert, apexTls: nil, now: now)[1]
            == LinkCheck(kind: .certificateIssuer, verdict: .danger, detail: "Your device doesn't trust this certificate: expired"))

        let noIssuer = TlsProbeResult.ok(trusted: true, trustError: nil, leaf: makeCertificate(issuerOrg: nil, issuerCommonName: nil, dnsNames: ["a.com"]))
        #expect(LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: noIssuer, apexTls: nil, now: now)[1].detail
            == "Issued by Unknown issuer, which isn't a common certificate authority.")
    }

    @Test func issuerMatchingIsACaseInsensitivePrefix() {
        let digicert = TlsProbeResult.ok(trusted: true, trustError: nil, leaf: makeCertificate(issuerOrg: "DigiCert Inc", dnsNames: ["a.com"]))
        #expect(LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: digicert, apexTls: nil, now: now)[1].verdict == .safe)
        let lower = TlsProbeResult.ok(trusted: true, trustError: nil, leaf: makeCertificate(issuerOrg: "google trust services llc", dnsNames: ["a.com"]))
        #expect(LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: lower, apexTls: nil, now: now)[1].verdict == .safe)
    }

    @Test func subdomainCertificates() {
        func probe(der: String, issued: Date) -> TlsProbeResult {
            .ok(trusted: true, trustError: nil, leaf: makeCertificate(der: der, notBefore: issued, dnsNames: ["login.a.com", "a.com"]))
        }
        func subdomainCheck(host: TlsProbeResult, apex: TlsProbeResult?) -> LinkCheck? {
            LinkChecks.checkTls(host: "login.a.com", apex: "a.com", hostTls: host, apexTls: apex, now: now).first { $0.kind == .subdomainCertificate }
        }

        let shared = subdomainCheck(host: probe(der: "SAME", issued: now), apex: probe(der: "SAME", issued: now))
        #expect(shared == LinkCheck(kind: .subdomainCertificate, verdict: .safe, detail: "login.a.com uses the same certificate as a.com."))

        let fresh = subdomainCheck(host: probe(der: "H", issued: now.addingTimeInterval(-3_600)), apex: probe(der: "A", issued: now.addingTimeInterval(-90 * 86_400)))
        #expect(fresh?.verdict == .danger)
        #expect(fresh?.detail == "login.a.com got its own certificate only 1 hours ago, on an otherwise established domain.")

        let established = subdomainCheck(host: probe(der: "H", issued: now.addingTimeInterval(-30 * 86_400)), apex: probe(der: "A", issued: now.addingTimeInterval(-90 * 86_400)))
        #expect(established == LinkCheck(kind: .subdomainCertificate, verdict: .safe, detail: "login.a.com has its own certificate, issued 30 days ago."))

        // Nothing to compare against when the apex can't be probed, or for the apex itself.
        #expect(subdomainCheck(host: probe(der: "H", issued: now), apex: nil) == nil)
        #expect(subdomainCheck(host: probe(der: "H", issued: now), apex: .failure("x")) == nil)
        #expect(LinkChecks.checkTls(host: "a.com", apex: "a.com", hostTls: probe(der: "H", issued: now), apexTls: probe(der: "A", issued: now), now: now).count == 2)
    }
}
