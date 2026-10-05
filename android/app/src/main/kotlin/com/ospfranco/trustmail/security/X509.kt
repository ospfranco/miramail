package com.ospfranco.trustmail.security

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

// Minimal DER/X.509 reader: just the fields the link checks need.

data class Certificate(
    /** Base64 DER, used to tell whether two hosts serve the same certificate. */
    val der: String,
    val notBefore: Instant,
    val notAfter: Instant,
    val issuerOrg: String?,
    val issuerCommonName: String?,
    val subjectCommonName: String?,
    val dnsNames: List<String>,
    val ipAddresses: List<String>,
)

class X509Exception : Exception("Malformed certificate")

private object Tag {
    const val UTF8_STRING = 0x0c
    const val UTC_TIME = 0x17
    const val BMP_STRING = 0x1e
    const val VERSION = 0xa0
    const val EXTENSIONS = 0xa3
    const val SAN_DNS_NAME = 0x82
    const val SAN_IP_ADDRESS = 0x87
}

private object Oid {
    const val COMMON_NAME = "550403"
    const val ORGANIZATION = "55040a"
    const val SUBJECT_ALT_NAME = "551d11"
}

private class Node(val tag: Int, val start: Int, val end: Int)

private class DerReader(private val bytes: ByteArray) {
    private fun byteAt(index: Int) = bytes[index].toInt() and 0xff

    fun readNode(offset: Int): Node {
        if (offset + 1 >= bytes.size) throw X509Exception()
        val tag = byteAt(offset)
        var length = byteAt(offset + 1)
        var start = offset + 2
        if (length and 0x80 != 0) {
            val count = length and 0x7f
            if (count > 4 || start + count > bytes.size) throw X509Exception()
            length = 0
            repeat(count) { length = length * 256 + byteAt(start++) }
        }
        if (start + length > bytes.size) throw X509Exception()
        return Node(tag, start, start + length)
    }

    fun children(node: Node): List<Node> {
        val out = ArrayList<Node>()
        var offset = node.start
        while (offset < node.end) {
            val child = readNode(offset)
            out.add(child)
            offset = child.end
        }
        return out
    }

    fun content(node: Node): ByteArray = bytes.copyOfRange(node.start, node.end)

    fun hex(node: Node): String = content(node).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun latin1(node: Node): String = String(content(node), Charsets.ISO_8859_1)

    fun readString(node: Node): String = when (node.tag) {
        Tag.UTF8_STRING -> utf8Decode(content(node))
        Tag.BMP_STRING -> String(content(node), Charsets.UTF_16BE)
        else -> latin1(node)
    }

    fun readTime(node: Node): Instant {
        val s = latin1(node)
        fun number(from: Int, length: Int) = s.drop(from).take(length).toIntOrNull() ?: 0
        val year: Int
        val rest: Int
        if (node.tag == Tag.UTC_TIME) {
            val yy = number(0, 2)
            year = yy + if (yy < 50) 2000 else 1900
            rest = 2
        } else {
            year = number(0, 4)
            rest = 4
        }
        return try {
            LocalDateTime.of(year, number(rest, 2), number(rest + 2, 2), number(rest + 4, 2), number(rest + 6, 2), number(rest + 8, 2))
                .toInstant(ZoneOffset.UTC)
        } catch (e: java.time.DateTimeException) {
            throw X509Exception()
        }
    }

    /** Reads the attributes (keyed by OID) of an X.500 Name, keeping the first value of each. */
    fun readName(name: Node): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        for (set in children(name)) {
            for (pair in children(set)) {
                val parts = children(pair)
                if (parts.size < 2) continue
                attributes.putIfAbsent(hex(parts[0]), readString(parts[1]))
            }
        }
        return attributes
    }
}

private fun formatIp(octets: ByteArray): String {
    if (octets.size == 4) return octets.joinToString(".") { (it.toInt() and 0xff).toString() }
    return (0 until octets.size / 2).joinToString(":") { i ->
        (((octets[i * 2].toInt() and 0xff) shl 8) or (octets[i * 2 + 1].toInt() and 0xff)).toString(16)
    }
}

@Throws(X509Exception::class)
fun parseCertificate(der: String): Certificate {
    val reader = DerReader(base64ToBytes(der))
    val certificate = reader.readNode(0)
    val tbs = reader.children(certificate).firstOrNull() ?: throw X509Exception()
    val fields = reader.children(tbs)
    // Skip the optional explicit version, then: serial, signature algorithm, issuer, validity, subject.
    val base = if (fields.firstOrNull()?.tag == Tag.VERSION) 1 else 0
    if (fields.size <= base + 4) throw X509Exception()
    val issuer = reader.readName(fields[base + 2])
    val validity = reader.children(fields[base + 3])
    if (validity.size < 2) throw X509Exception()
    val notBefore = reader.readTime(validity[0])
    val notAfter = reader.readTime(validity[1])
    val subject = reader.readName(fields[base + 4])

    val dnsNames = ArrayList<String>()
    val ipAddresses = ArrayList<String>()
    val extensions = fields.firstOrNull { it.tag == Tag.EXTENSIONS }
    val list = extensions?.let { reader.children(it).firstOrNull() }
    if (list != null) {
        for (extension in reader.children(list)) {
            val parts = reader.children(extension)
            val oid = parts.firstOrNull() ?: continue
            if (reader.hex(oid) != Oid.SUBJECT_ALT_NAME) continue
            // The last part is an OCTET STRING (after the optional "critical" flag) wrapping the GeneralNames sequence.
            val generalNames = reader.children(parts.last()).firstOrNull() ?: continue
            for (name in reader.children(generalNames)) {
                if (name.tag == Tag.SAN_DNS_NAME) dnsNames.add(reader.latin1(name).lowercase())
                if (name.tag == Tag.SAN_IP_ADDRESS) ipAddresses.add(formatIp(reader.content(name)))
            }
        }
    }

    return Certificate(
        der = der,
        notBefore = notBefore,
        notAfter = notAfter,
        issuerOrg = issuer[Oid.ORGANIZATION],
        issuerCommonName = issuer[Oid.COMMON_NAME],
        subjectCommonName = subject[Oid.COMMON_NAME],
        dnsNames = dnsNames,
        ipAddresses = ipAddresses,
    )
}

/** RFC 6125 matching: a wildcard covers exactly one leftmost label. */
fun certificateMatchesHost(cert: Certificate, host: String): Boolean {
    val target = host.lowercase().removeSuffix(".")
    if (target in cert.ipAddresses) return true
    // Legacy fallback: the subject CN only counts when there are no SAN entries.
    val names = cert.dnsNames.ifEmpty { listOfNotNull(cert.subjectCommonName?.lowercase()) }
    return names.any { name ->
        if (name == target) return@any true
        if (!name.startsWith("*.")) return@any false
        val dot = target.indexOf('.')
        dot > 0 && target.substring(dot + 1) == name.substring(2)
    }
}
