import Testing
@testable import Safemail

struct BytesTests {
    @Test func decodesUnpaddedUrlSafeBase64() {
        #expect(utf8Decode(base64ToBytes("SGVsbG8")) == "Hello")
        #expect(utf8Decode(base64ToBytes("4pyTIMOgIGxhIG1vZGUgPG9rPg")) == "✓ à la mode <ok>")
        #expect(utf8Decode(base64ToBytes("PGI-aGk8L2I-Pz4-")) == "<b>hi</b>?>>")
    }

    @Test func decodesStandardPaddedBase64AndIgnoresWhitespace() {
        #expect(utf8Decode(base64ToBytes("PGI+aGk8L2I+Pz4+")) == "<b>hi</b>?>>")
        #expect(utf8Decode(base64ToBytes("SGVs\nbG8=")) == "Hello")
        #expect(base64ToBytes("").isEmpty)
    }

    @Test func encodesUriComponentsLikeJavaScript() {
        #expect(encodeURIComponent("a+b@x.com") == "a%2Bb%40x.com")
        #expect(encodeURIComponent("{from:\"x\" to:\"x\"}") == "%7Bfrom%3A%22x%22%20to%3A%22x%22%7D")
        #expect(encodeURIComponent("safe-_.!~*'()") == "safe-_.!~*'()")
        #expect(encodeURIComponent("é") == "%C3%A9")
    }
}
