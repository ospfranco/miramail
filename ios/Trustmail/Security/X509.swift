import Foundation

// Minimal DER/X.509 reader: just the fields the link checks need.

struct Certificate: Sendable, Equatable {
    /// Base64 DER, used to tell whether two hosts serve the same certificate.
    let der: String
    let notBefore: Date
    let notAfter: Date
    let issuerOrg: String?
    let issuerCommonName: String?
    let subjectCommonName: String?
    let dnsNames: [String]
    let ipAddresses: [String]
}

struct X509Error: Error, LocalizedError {
    let errorDescription: String? = "Malformed certificate"
}

private enum Tag {
    static let utf8String: UInt8 = 0x0c
    static let utcTime: UInt8 = 0x17
    static let bmpString: UInt8 = 0x1e
    static let version: UInt8 = 0xa0
    static let extensions: UInt8 = 0xa3
    static let sanDnsName: UInt8 = 0x82
    static let sanIpAddress: UInt8 = 0x87
}

private enum Oid {
    static let commonName = "550403"
    static let organization = "55040a"
    static let subjectAltName = "551d11"
}

private struct Node {
    let tag: UInt8
    let start: Int
    let end: Int
}

private struct DerReader {
    let bytes: [UInt8]

    func readNode(at offset: Int) throws -> Node {
        guard offset + 1 < bytes.count else { throw X509Error() }
        let tag = bytes[offset]
        var length = Int(bytes[offset + 1])
        var start = offset + 2
        if length & 0x80 != 0 {
            let count = length & 0x7f
            guard count <= 4, start + count <= bytes.count else { throw X509Error() }
            length = 0
            for _ in 0..<count {
                length = length * 256 + Int(bytes[start])
                start += 1
            }
        }
        guard start + length <= bytes.count else { throw X509Error() }
        return Node(tag: tag, start: start, end: start + length)
    }

    func children(of node: Node) throws -> [Node] {
        var out: [Node] = []
        var offset = node.start
        while offset < node.end {
            let child = try readNode(at: offset)
            out.append(child)
            offset = child.end
        }
        return out
    }

    func content(_ node: Node) -> ArraySlice<UInt8> { bytes[node.start..<node.end] }

    func hex(_ node: Node) -> String {
        content(node).map { String(format: "%02x", $0) }.joined()
    }

    func latin1(_ node: Node) -> String {
        String(content(node).map { Character(UnicodeScalar($0)) })
    }

    func readString(_ node: Node) -> String {
        switch node.tag {
        case Tag.utf8String:
            return utf8Decode(Array(content(node)))
        case Tag.bmpString:
            let data = Array(content(node))
            var units: [UInt16] = []
            var i = 0
            while i + 1 < data.count {
                units.append(UInt16(data[i]) << 8 | UInt16(data[i + 1]))
                i += 2
            }
            return String(decoding: units, as: UTF16.self)
        default:
            return latin1(node)
        }
    }

    func readTime(_ node: Node) throws -> Date {
        let s = Array(latin1(node))
        func number(_ from: Int, _ length: Int) -> Int {
            guard from + length <= s.count else { return 0 }
            return Int(String(s[from..<from + length])) ?? 0
        }
        var parts = DateComponents()
        let rest: Int
        if node.tag == Tag.utcTime {
            let yy = number(0, 2)
            parts.year = yy + (yy < 50 ? 2000 : 1900)
            rest = 2
        } else {
            parts.year = number(0, 4)
            rest = 4
        }
        parts.month = number(rest, 2)
        parts.day = number(rest + 2, 2)
        parts.hour = number(rest + 4, 2)
        parts.minute = number(rest + 6, 2)
        parts.second = number(rest + 8, 2)

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: parts) else { throw X509Error() }
        return date
    }

    /// Reads the attributes (keyed by OID) of an X.500 Name, keeping the first value of each.
    func readName(_ name: Node) throws -> [String: String] {
        var attributes: [String: String] = [:]
        for set in try children(of: name) {
            for pair in try children(of: set) {
                let parts = try children(of: pair)
                guard parts.count >= 2 else { continue }
                let key = hex(parts[0])
                if attributes[key] == nil { attributes[key] = readString(parts[1]) }
            }
        }
        return attributes
    }
}

private func formatIp(_ octets: [UInt8]) -> String {
    if octets.count == 4 { return octets.map(String.init).joined(separator: ".") }
    var groups: [String] = []
    var i = 0
    while i + 1 < octets.count {
        groups.append(String(UInt16(octets[i]) << 8 | UInt16(octets[i + 1]), radix: 16))
        i += 2
    }
    return groups.joined(separator: ":")
}

func parseCertificate(der: String) throws -> Certificate {
    let reader = DerReader(bytes: base64ToBytes(der))
    let certificate = try reader.readNode(at: 0)
    guard let tbs = try reader.children(of: certificate).first else { throw X509Error() }
    let fields = try reader.children(of: tbs)
    // Skip the optional explicit version, then: serial, signature algorithm, issuer, validity, subject.
    let base = fields.first?.tag == Tag.version ? 1 : 0
    guard fields.count > base + 4 else { throw X509Error() }
    let issuer = try reader.readName(fields[base + 2])
    let validity = try reader.children(of: fields[base + 3])
    guard validity.count >= 2 else { throw X509Error() }
    let notBefore = try reader.readTime(validity[0])
    let notAfter = try reader.readTime(validity[1])
    let subject = try reader.readName(fields[base + 4])

    var dnsNames: [String] = []
    var ipAddresses: [String] = []
    if let extensions = fields.first(where: { $0.tag == Tag.extensions }),
       let list = try reader.children(of: extensions).first {
        for ext in try reader.children(of: list) {
            let parts = try reader.children(of: ext)
            guard let oid = parts.first, reader.hex(oid) == Oid.subjectAltName, let value = parts.last else { continue }
            // `value` is an OCTET STRING (after the optional "critical" flag) wrapping the GeneralNames sequence.
            guard let generalNames = try reader.children(of: value).first else { continue }
            for name in try reader.children(of: generalNames) {
                if name.tag == Tag.sanDnsName { dnsNames.append(reader.latin1(name).lowercased()) }
                if name.tag == Tag.sanIpAddress { ipAddresses.append(formatIp(Array(reader.content(name)))) }
            }
        }
    }

    return Certificate(
        der: der,
        notBefore: notBefore,
        notAfter: notAfter,
        issuerOrg: issuer[Oid.organization],
        issuerCommonName: issuer[Oid.commonName],
        subjectCommonName: subject[Oid.commonName],
        dnsNames: dnsNames,
        ipAddresses: ipAddresses
    )
}

/// RFC 6125 matching: a wildcard covers exactly one leftmost label.
func certificateMatchesHost(_ cert: Certificate, host: String) -> Bool {
    var target = host.lowercased()
    if target.hasSuffix(".") { target.removeLast() }
    if cert.ipAddresses.contains(target) { return true }
    // Legacy fallback: the subject CN only counts when there are no SAN entries.
    let names = !cert.dnsNames.isEmpty
        ? cert.dnsNames
        : (cert.subjectCommonName.map { [$0.lowercased()] } ?? [])
    return names.contains { name in
        if name == target { return true }
        guard name.hasPrefix("*."), let dot = target.firstIndex(of: "."), dot != target.startIndex else { return false }
        return target[target.index(after: dot)...] == name.dropFirst(2)
    }
}
