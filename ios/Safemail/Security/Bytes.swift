import Foundation

private let base64Alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)

private let base64Values: [UInt8: UInt8] = {
    var values: [UInt8: UInt8] = [:]
    for (index, char) in base64Alphabet.enumerated() { values[char] = UInt8(index) }
    // Standard alphabet aliases for the URL-safe '-' and '_'.
    values[UInt8(ascii: "+")] = 62
    values[UInt8(ascii: "/")] = 63
    return values
}()

/// Decodes standard or URL-safe base64, ignoring padding and whitespace.
/// Foundation's decoder rejects Gmail's unpadded URL-safe data, so this is done by hand.
func base64ToBytes(_ data: String) -> [UInt8] {
    var bytes: [UInt8] = []
    var buffer: UInt32 = 0
    var bits = 0
    for char in data.utf8 {
        guard let value = base64Values[char] else { continue }
        buffer = (buffer << 6) | UInt32(value)
        bits += 6
        if bits >= 8 {
            bits -= 8
            bytes.append(UInt8((buffer >> UInt32(bits)) & 0xff))
            buffer &= (1 << UInt32(bits)) - 1
        }
    }
    return bytes
}

/// URL-safe base64 without padding, the form the Gmail API wants for raw messages.
func base64UrlEncode(_ bytes: [UInt8]) -> String {
    Data(bytes).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

func utf8Decode(_ bytes: [UInt8]) -> String {
    String(decoding: bytes, as: UTF8.self)
}

/// Equivalent of JavaScript's `encodeURIComponent`: `+`, `&` and `=` are escaped too.
func encodeURIComponent(_ value: String) -> String {
    let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")
    return value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
}
