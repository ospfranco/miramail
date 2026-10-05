package com.ospfranco.trustmail

import com.ospfranco.trustmail.security.base64ToBytes
import com.ospfranco.trustmail.security.base64UrlEncode
import com.ospfranco.trustmail.security.utf8Decode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BytesTest {
    @Test
    fun decodesUnpaddedUrlSafeBase64() {
        assertEquals("Hello", utf8Decode(base64ToBytes("SGVsbG8")))
        assertEquals("✓ à la mode <ok>", utf8Decode(base64ToBytes("4pyTIMOgIGxhIG1vZGUgPG9rPg")))
        assertEquals("<b>hi</b>?>>", utf8Decode(base64ToBytes("PGI-aGk8L2I-Pz4-")))
    }

    @Test
    fun decodesStandardPaddedBase64AndIgnoresWhitespace() {
        assertEquals("<b>hi</b>?>>", utf8Decode(base64ToBytes("PGI+aGk8L2I+Pz4+")))
        assertEquals("Hello", utf8Decode(base64ToBytes("SGVs\nbG8=")))
        assertTrue(base64ToBytes("").isEmpty())
    }

    @Test
    fun encodesUrlSafeBase64WithoutPadding() {
        assertEquals("PGI-aGk8L2I-Pz4-", base64UrlEncode("<b>hi</b>?>>".toByteArray()))
        assertEquals("-__-", base64UrlEncode(byteArrayOf(-5, -1, -2)))
        assertEquals("SGVsbG8", base64UrlEncode("Hello".toByteArray()))
        assertEquals("", base64UrlEncode(ByteArray(0)))
        // Round trip through the decoder used for Gmail's own data.
        assertEquals("✓ à la mode", utf8Decode(base64ToBytes(base64UrlEncode("✓ à la mode".toByteArray()))))
    }
}
