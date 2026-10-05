package com.ospfranco.trustmail

import com.ospfranco.trustmail.security.X509Exception
import com.ospfranco.trustmail.security.certificateMatchesHost
import com.ospfranco.trustmail.security.parseCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class X509Test {
    @Test
    fun parsesLeafCertificate() {
        val cert = parseCertificate(Fixture.text("leaf.der.b64"))
        assertEquals(date("2026-03-01T12:00:00Z"), cert.notBefore)
        assertEquals(date("2026-05-30T12:00:00Z"), cert.notAfter)
        assertEquals("Let's Encrypt", cert.issuerOrg)
        assertEquals("R11", cert.issuerCommonName)
        assertEquals("example.com", cert.subjectCommonName)
        assertEquals(listOf("example.com", "*.example.com"), cert.dnsNames)
        assertEquals(listOf("93.184.216.34", "2606:2800:220:1:0:0:0:1"), cert.ipAddresses)
    }

    @Test
    fun parsesCertificateWithoutSubjectAlternativeNames() {
        val cert = parseCertificate(Fixture.text("cn.der.b64"))
        assertTrue(cert.dnsNames.isEmpty())
        assertEquals("legacy.example.org", cert.subjectCommonName)
        assertEquals("Acme Corp", cert.issuerOrg)
        assertEquals(date("2025-01-01T00:00:00Z"), cert.notBefore)
    }

    @Test
    fun rejectsGarbage() {
        assertThrows(X509Exception::class.java) { parseCertificate("") }
        assertThrows(X509Exception::class.java) { parseCertificate("MIIB") }
        assertThrows(X509Exception::class.java) { parseCertificate(Fixture.text("leaf.der.b64").take(200)) }
    }

    @Test
    fun matchesHostnames() {
        val cert = parseCertificate(Fixture.text("leaf.der.b64"))
        assertTrue(certificateMatchesHost(cert, "example.com"))
        assertTrue(certificateMatchesHost(cert, "EXAMPLE.com."))
        assertTrue(certificateMatchesHost(cert, "www.example.com"))
        assertFalse(certificateMatchesHost(cert, "a.b.example.com")) // a wildcard covers one label
        assertFalse(certificateMatchesHost(cert, "evil.com"))
        assertFalse(certificateMatchesHost(cert, "notexample.com"))
        assertTrue(certificateMatchesHost(cert, "93.184.216.34"))
    }

    @Test
    fun fallsBackToCommonNameOnlyWithoutSan() {
        val legacy = parseCertificate(Fixture.text("cn.der.b64"))
        assertTrue(certificateMatchesHost(legacy, "legacy.example.org"))
        assertFalse(certificateMatchesHost(legacy, "other.example.org"))
        // The CN is ignored once SAN entries exist.
        val withSan = makeCertificate(subjectCommonName = "cn.example.com", dnsNames = listOf("san.example.com"))
        assertFalse(certificateMatchesHost(withSan, "cn.example.com"))
        assertTrue(certificateMatchesHost(withSan, "san.example.com"))
    }
}
