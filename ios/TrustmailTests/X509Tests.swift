import Foundation
import Testing
@testable import Trustmail

struct X509Tests {
    @Test func parsesLeafCertificate() throws {
        let cert = try parseCertificate(der: Fixture.text("leaf.der", ext: "b64"))
        #expect(cert.notBefore == date("2026-03-01T12:00:00Z"))
        #expect(cert.notAfter == date("2026-05-30T12:00:00Z"))
        #expect(cert.issuerOrg == "Let's Encrypt")
        #expect(cert.issuerCommonName == "R11")
        #expect(cert.subjectCommonName == "example.com")
        #expect(cert.dnsNames == ["example.com", "*.example.com"])
        #expect(cert.ipAddresses == ["93.184.216.34", "2606:2800:220:1:0:0:0:1"])
    }

    @Test func parsesCertificateWithoutSubjectAlternativeNames() throws {
        let cert = try parseCertificate(der: Fixture.text("cn.der", ext: "b64"))
        #expect(cert.dnsNames.isEmpty)
        #expect(cert.subjectCommonName == "legacy.example.org")
        #expect(cert.issuerOrg == "Acme Corp")
        #expect(cert.notBefore == date("2025-01-01T00:00:00Z"))
    }

    @Test func rejectsGarbage() {
        #expect(throws: X509Error.self) { try parseCertificate(der: "") }
        #expect(throws: X509Error.self) { try parseCertificate(der: "MIIB") }
        let truncated = String(Fixture.text("leaf.der", ext: "b64").prefix(200))
        #expect(throws: X509Error.self) { try parseCertificate(der: truncated) }
    }

    @Test func matchesHostnames() throws {
        let cert = try parseCertificate(der: Fixture.text("leaf.der", ext: "b64"))
        #expect(certificateMatchesHost(cert, host: "example.com"))
        #expect(certificateMatchesHost(cert, host: "EXAMPLE.com."))
        #expect(certificateMatchesHost(cert, host: "www.example.com"))
        #expect(!certificateMatchesHost(cert, host: "a.b.example.com")) // a wildcard covers one label
        #expect(!certificateMatchesHost(cert, host: "evil.com"))
        #expect(!certificateMatchesHost(cert, host: "notexample.com"))
        #expect(certificateMatchesHost(cert, host: "93.184.216.34"))
    }

    @Test func fallsBackToCommonNameOnlyWithoutSan() throws {
        let legacy = try parseCertificate(der: Fixture.text("cn.der", ext: "b64"))
        #expect(certificateMatchesHost(legacy, host: "legacy.example.org"))
        #expect(!certificateMatchesHost(legacy, host: "other.example.org"))
        // The CN is ignored once SAN entries exist.
        let withSan = makeCertificate(subjectCommonName: "cn.example.com", dnsNames: ["san.example.com"])
        #expect(!certificateMatchesHost(withSan, host: "cn.example.com"))
        #expect(certificateMatchesHost(withSan, host: "san.example.com"))
    }
}
