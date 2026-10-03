import Foundation
import Testing
@testable import Safemail

/// Serves canned responses so Gmail parsing can be tested without the network.
final class StubProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) -> (Int, String)
    nonisolated(unsafe) static var handler: Handler = { _ in (404, "") }
    private static let lock = NSLock()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let (status, body) = Self.lock.withLock { Self.handler(request) }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    static func set(_ handler: @escaping Handler) { lock.withLock { self.handler = handler } }
}

/// Tests share the stub, so they run one at a time.
@Suite(.serialized)
struct GmailClientTests {
    private func client(tokens: TokenLog = TokenLog()) -> GmailClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return GmailClient(session: URLSession(configuration: config)) { forceRefresh in
            tokens.record(forceRefresh)
            return forceRefresh ? "fresh-token" : "old-token"
        }
    }

    final class TokenLog: @unchecked Sendable {
        private let lock = NSLock()
        private var requests: [Bool] = []
        func record(_ forceRefresh: Bool) { lock.withLock { requests.append(forceRefresh) } }
        var all: [Bool] { lock.withLock { requests } }
    }

    private func b64url(_ s: String) -> String {
        Data(s.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    @Test func listsInboxInOrderWithMetadata() async throws {
        StubProtocol.set { request in
            let url = request.url!.absoluteString
            if url.contains("/messages?labelIds=INBOX&maxResults=20") {
                return (200, #"{"messages":[{"id":"a"},{"id":"b"}]}"#)
            }
            // The first message answers slower, to prove results keep inbox order.
            if url.contains("/messages/a?") { Thread.sleep(forTimeInterval: 0.2) }
            let id = url.contains("/messages/a?") ? "a" : "b"
            let labels = id == "a" ? #","labelIds":["INBOX","UNREAD"]"# : ""
            let subject = id == "a" ? "" : #"{"name":"subject","value":"Hello"},"#
            return (200, #"{"id":"\#(id)","threadId":"t\#(id)","snippet":"snip \#(id)"\#(labels),"payload":{"headers":[\#(subject){"name":"From","value":"Ann <ann@x.com>"}]}}"#)
        }
        let messages = try await client().listInbox(maxResults: 20)
        #expect(messages == [
            MessageSummary(id: "a", threadId: "ta", from: "Ann <ann@x.com>", subject: "(no subject)", snippet: "snip a", unread: true),
            MessageSummary(id: "b", threadId: "tb", from: "Ann <ann@x.com>", subject: "Hello", snippet: "snip b", unread: false),
        ])
    }

    @Test func emptyInbox() async throws {
        StubProtocol.set { _ in (200, "{}") }
        #expect(try await client().listInbox(maxResults: 20).isEmpty)
    }

    @Test func parsesMultipartMessagePreferringHtmlAndSkippingAttachments() async throws {
        let html = "<p>✓ Hi</p>"
        let body = """
        {"id":"m1","threadId":"t1","snippet":"snippet","payload":{"mimeType":"multipart/mixed",
         "headers":[{"name":"From","value":"\\"Ann\\" <Ann@Example.COM>"},{"name":"To","value":"me@x.com"},{"name":"Subject","value":"Report"},{"name":"Date","value":"Fri, 2 Oct 2026"},
           {"name":"Authentication-Results","value":"mx.google.com; dmarc=pass header.from=example.com"},
           {"name":"Authentication-Results","value":"evil.example; dmarc=pass"},
           {"name":"authentication-results","value":"  MX.GOOGLE.COM ; spf=pass"}],
         "body":{"size":0},
         "parts":[
           {"mimeType":"multipart/alternative","body":{"size":0},"parts":[
             {"mimeType":"text/plain","body":{"size":3,"data":"\(b64url("plain"))"}},
             {"mimeType":"text/html","body":{"size":3,"data":"\(b64url(html))"}}]},
           {"mimeType":"text/html","filename":"attachment.html","body":{"size":3,"data":"\(b64url("<b>attachment</b>"))"}}]}}
        """
        StubProtocol.set { _ in (200, body) }
        let message = try await client().getMessage(id: "m1")
        #expect(message.body == html)
        #expect(message.bodyType == .html)
        #expect(message.fromAddress == "ann@example.com")
        #expect(message.subject == "Report")
        #expect(message.to == "me@x.com")
        #expect(message.date == "Fri, 2 Oct 2026")
        // Only Gmail's own results count; other servers' headers can be forged by the sender.
        #expect(message.authenticationResults == ["mx.google.com; dmarc=pass header.from=example.com", "  MX.GOOGLE.COM ; spf=pass"])
    }

    @Test func fallsBackToPlainTextThenSnippet() async throws {
        StubProtocol.set { _ in (200, #"{"id":"m","threadId":"t","snippet":"just a snippet","payload":{"mimeType":"text/plain","headers":[{"name":"From","value":"bare@x.com"}],"body":{"size":5,"data":"\#(b64url("plain body"))"}}}"#) }
        let plain = try await client().getMessage(id: "m")
        #expect(plain.body == "plain body")
        #expect(plain.bodyType == .text)
        #expect(plain.fromAddress == "bare@x.com")
        #expect(plain.subject == "(no subject)")

        StubProtocol.set { _ in (200, #"{"id":"m","threadId":"t","snippet":"just a snippet","payload":{"mimeType":"multipart/mixed","headers":[],"body":{"size":0}}}"#) }
        let empty = try await client().getMessage(id: "m")
        #expect(empty.body == "just a snippet")
        #expect(empty.bodyType == .text)
    }

    @Test func retriesOnceWithAFreshTokenAfter401() async throws {
        StubProtocol.set { request in
            request.value(forHTTPHeaderField: "Authorization") == "Bearer fresh-token" ? (200, "{}") : (401, "expired")
        }
        let tokens = TokenLog()
        _ = try await client(tokens: tokens).listInbox(maxResults: 5)
        #expect(tokens.all == [false, true])
    }

    @Test func surfacesApiErrors() async {
        StubProtocol.set { _ in (403, "denied") }
        await #expect(throws: GmailError.self) { try await client().listInbox(maxResults: 5) }
        do { _ = try await client().listInbox(maxResults: 5) } catch {
            #expect(error.localizedDescription == "Gmail API 403: denied")
        }
    }

    @Test func correspondenceQueryEscapesPlusAddressesAndIgnoresTheCurrentMessage() async throws {
        let seen = TokenLog()
        nonisolated(unsafe) var queries: [String] = []
        let lock = NSLock()
        StubProtocol.set { request in
            lock.withLock { queries.append(request.url!.absoluteString) }
            return (200, #"{"messages":[{"id":"current"}]}"#)
        }
        let known = try await client(tokens: seen).hasCorrespondence(with: "a+tag@x.com", excluding: "current")
        #expect(!known)
        #expect(lock.withLock { queries }.first?.hasSuffix("/messages?q=%7Bfrom%3A%22a%2Btag%40x.com%22%20to%3A%22a%2Btag%40x.com%22%7D&maxResults=2") == true)

        StubProtocol.set { _ in (200, #"{"messages":[{"id":"current"},{"id":"other"}]}"#) }
        #expect(try await client().hasCorrespondence(with: "a@x.com", excluding: "current"))
    }
}
