package com.ospfranco.trustmail.security

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** Decodes standard or URL-safe base64, ignoring padding and whitespace. */
fun base64ToBytes(data: String): ByteArray {
    val out = ArrayList<Byte>(data.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (char in data) {
        val value = BASE64_ALPHABET.indexOf(
            when (char) {
                '+' -> '-'
                '/' -> '_'
                else -> char
            },
        )
        if (value == -1) continue
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xff).toByte())
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    return out.toByteArray()
}

fun utf8Decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

/** URL-safe base64 without padding, the form the Gmail API wants for raw messages. */
fun base64UrlEncode(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
