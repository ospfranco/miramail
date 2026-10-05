package com.ospfranco.trustmail.security

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class TlsInspection(
    /** Certificate chain as presented by the server, leaf first, base64-encoded DER. */
    val chain: List<String>,
    /** Whether the platform trusts the chain for this host (CA trust + hostname). */
    val trusted: Boolean,
    val trustError: String?,
)

class TlsConnectException(message: String) : Exception(message)

object TlsInspector {
    /** Resolves [host] with the system resolver (the same one the browser will use). */
    suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the certificate chain the server presents for [host], leaf first. Performs only the TLS
     * handshake, never sends an HTTP request. The trust manager records the chain and the platform's
     * verdict, then delegates to the default validation (so an untrusted chain still aborts the
     * handshake; we just keep what we captured).
     */
    suspend fun inspect(host: String, port: Int = 443, timeoutMs: Int): TlsInspection = withContext(Dispatchers.IO) {
        val defaultTrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        var chain: Array<X509Certificate> = emptyArray()
        var trustError: String? = null
        val recordingTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) =
                defaultTrustManager.checkClientTrusted(certs, authType)

            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                chain = certs
                try {
                    defaultTrustManager.checkServerTrusted(certs, authType)
                } catch (e: CertificateException) {
                    trustError = e.message ?: "Certificate is not trusted"
                    throw e
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.acceptedIssuers
        }

        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recordingTrustManager), null) }
        var hostnameMatches = false
        try {
            (context.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                socket.startHandshake()
                hostnameMatches = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)
            }
        } catch (e: SSLException) {
            // Untrusted chain: the handshake aborts, but the chain was already captured above.
            if (chain.isEmpty()) throw TlsConnectException(e.message ?: "TLS handshake failed")
        } catch (e: Exception) {
            throw TlsConnectException(e.message ?: "Could not connect to $host")
        }

        if (trustError == null && !hostnameMatches) trustError = "Certificate does not match $host"
        TlsInspection(
            chain = chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) },
            trusted = trustError == null,
            trustError = trustError,
        )
    }
}
