import Foundation
import Security

struct TlsInspection: Sendable {
    /// Certificate chain as presented by the server, leaf first, base64-encoded DER.
    let chain: [String]
    /// Whether the platform trusts the chain for this host (CA trust + hostname).
    let trusted: Bool
    let trustError: String?
}

struct TlsInspectionError: Error, LocalizedError {
    let errorDescription: String?
}

enum TlsInspector {
    /// Returns the certificate chain the server presents for `host`, leaf first.
    /// Only the TLS handshake happens; no HTTP request (path, query, cookies) is ever sent.
    static func inspect(host: String, port: Int = 443, timeout: TimeInterval) async throws -> TlsInspection {
        try await withCheckedThrowingContinuation { continuation in
            TlsProbe(host: host, port: port, timeout: timeout, continuation: continuation).start()
        }
    }

    /// Resolves `host` with the system resolver (the same one the browser will use).
    static func resolve(host: String) async -> [String] {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: resolveBlocking(host))
            }
        }
    }
}

/// Opens a TLS connection only far enough to read the server's certificate chain, then cancels
/// the authentication challenge so no HTTP request is ever sent.
private final class TlsProbe: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let host: String
    private let port: Int
    private let timeout: TimeInterval
    private let lock = NSLock()
    private var continuation: CheckedContinuation<TlsInspection, Error>?
    private var session: URLSession?
    // Keeps the probe alive until the session calls back.
    private var retainSelf: TlsProbe?

    init(host: String, port: Int, timeout: TimeInterval, continuation: CheckedContinuation<TlsInspection, Error>) {
        self.host = host
        self.port = port
        self.timeout = timeout
        self.continuation = continuation
    }

    func start() {
        guard let url = URL(string: "https://\(host):\(port)/") else {
            finish(.failure(TlsInspectionError(errorDescription: "Invalid host: \(host)")))
            return
        }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.urlCache = nil
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        lock.withLock {
            self.session = session
            retainSelf = self
        }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        session.dataTask(with: request).resume()
    }

    private func finish(_ result: Result<TlsInspection, Error>) {
        let (continuation, session) = lock.withLock { () -> (CheckedContinuation<TlsInspection, Error>?, URLSession?) in
            defer {
                self.continuation = nil
                self.session = nil
                retainSelf = nil
            }
            return (self.continuation, self.session)
        }
        guard let continuation else { return }
        continuation.resume(with: result)
        session?.invalidateAndCancel()
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
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
        let trustError = error.map { CFErrorCopyDescription($0) as String }

        completionHandler(.cancelAuthenticationChallenge, nil)
        finish(.success(TlsInspection(chain: der, trusted: trusted, trustError: trustError)))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(.failure(TlsInspectionError(errorDescription: error?.localizedDescription ?? "TLS handshake did not complete")))
    }
}

private func resolveBlocking(_ host: String) -> [String] {
    var hints = addrinfo()
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    var result: UnsafeMutablePointer<addrinfo>?
    guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else { return [] }
    defer { freeaddrinfo(first) }

    var addresses: [String] = []
    var cursor: UnsafeMutablePointer<addrinfo>? = first
    while let info = cursor {
        var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(info.pointee.ai_addr, info.pointee.ai_addrlen, &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 {
            let address = String(decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            if !addresses.contains(address) { addresses.append(address) }
        }
        cursor = info.pointee.ai_next
    }
    return addresses
}
