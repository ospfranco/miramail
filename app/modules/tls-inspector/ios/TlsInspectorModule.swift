import ExpoModulesCore
import Foundation

public class TlsInspectorModule: Module {
  public func definition() -> ModuleDefinition {
    Name("TlsInspector")

    // Returns the certificate chain the server presents for `host`, leaf first, as base64 DER.
    AsyncFunction("inspectTls") { (host: String, port: Int, timeoutMs: Int, promise: Promise) in
      TlsProbe(host: host, port: port, timeout: Double(timeoutMs) / 1000, promise: promise).start()
    }

    // Resolves `host` with the system resolver (the same one the browser will use).
    AsyncFunction("resolveHost") { (host: String) -> [String] in
      return resolve(host)
    }
  }
}

/// Opens a TLS connection only far enough to read the server's certificate chain, then cancels
/// the authentication challenge so no HTTP request (path, query, cookies) is ever sent.
private final class TlsProbe: NSObject, URLSessionDelegate, URLSessionTaskDelegate {
  private let host: String
  private let port: Int
  private let timeout: TimeInterval
  private let promise: Promise
  private var settled = false
  private var session: URLSession?
  // Keeps the probe alive until the session calls back.
  private var retainSelf: TlsProbe?

  init(host: String, port: Int, timeout: TimeInterval, promise: Promise) {
    self.host = host
    self.port = port
    self.timeout = timeout
    self.promise = promise
  }

  func start() {
    guard let url = URL(string: "https://\(host):\(port)/") else {
      promise.reject("ERR_TLS_INVALID_HOST", "Invalid host: \(host)")
      return
    }
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = timeout
    config.timeoutIntervalForResource = timeout
    config.urlCache = nil
    let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    self.session = session
    retainSelf = self
    var request = URLRequest(url: url)
    request.httpMethod = "HEAD"
    session.dataTask(with: request).resume()
  }

  private func finish(_ block: () -> Void) {
    guard !settled else { return }
    settled = true
    block()
    session?.invalidateAndCancel()
    retainSelf = nil
  }

  func urlSession(
    _ session: URLSession,
    didReceive challenge: URLAuthenticationChallenge,
    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
  ) {
    guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
          let trust = challenge.protectionSpace.serverTrust else {
      completionHandler(.cancelAuthenticationChallenge, nil)
      return
    }

    var error: CFError?
    let trusted = SecTrustEvaluateWithError(trust, &error)
    let chain = (SecTrustCopyCertificateChain(trust) as? [SecCertificate]) ?? []
    let der = chain.map { (SecCertificateCopyData($0) as Data).base64EncodedString() }

    var result: [String: Any] = ["chain": der, "trusted": trusted]
    if let error {
      result["trustError"] = CFErrorCopyDescription(error) as String
    }

    completionHandler(.cancelAuthenticationChallenge, nil)
    finish { promise.resolve(result) }
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    finish {
      promise.reject("ERR_TLS_CONNECT", error?.localizedDescription ?? "TLS handshake did not complete")
    }
  }
}

private func resolve(_ host: String) -> [String] {
  var hints = addrinfo()
  hints.ai_family = AF_UNSPEC
  hints.ai_socktype = SOCK_STREAM
  var result: UnsafeMutablePointer<addrinfo>?
  guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else { return [] }
  defer { freeaddrinfo(first) }

  var addresses = Set<String>()
  var cursor: UnsafeMutablePointer<addrinfo>? = first
  while let info = cursor {
    var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
    if getnameinfo(info.pointee.ai_addr, info.pointee.ai_addrlen, &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 {
      addresses.insert(String(cString: buffer))
    }
    cursor = info.pointee.ai_next
  }
  return Array(addresses)
}
