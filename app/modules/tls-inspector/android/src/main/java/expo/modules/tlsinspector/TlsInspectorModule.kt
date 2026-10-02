package expo.modules.tlsinspector

import android.util.Base64
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
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

class TlsConnectException(message: String) : CodedException("ERR_TLS_CONNECT", message, null)

class TlsInspectorModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("TlsInspector")

    // Returns the certificate chain the server presents for `host`, leaf first, as base64 DER.
    AsyncFunction("inspectTls") { host: String, port: Int, timeoutMs: Int ->
      inspectTls(host, port, timeoutMs)
    }

    // Resolves `host` with the system resolver (the same one the browser will use).
    AsyncFunction("resolveHost") { host: String ->
      try {
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
      } catch (e: Exception) {
        emptyList<String>()
      }
    }
  }

  /**
   * Performs only the TLS handshake, never sends an HTTP request. The trust manager records the
   * chain and the platform's verdict, then delegates to the default validation (so an untrusted
   * chain still aborts the handshake; we just keep what we captured).
   */
  private fun inspectTls(host: String, port: Int, timeoutMs: Int): Map<String, Any?> {
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
    return mapOf(
      "chain" to chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) },
      "trusted" to (trustError == null),
      "trustError" to trustError,
    )
  }
}
