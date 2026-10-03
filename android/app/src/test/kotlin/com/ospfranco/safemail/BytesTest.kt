package com.ospfranco.safemail

import com.ospfranco.safemail.security.base64ToBytes
import com.ospfranco.safemail.security.utf8Decode
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
}
