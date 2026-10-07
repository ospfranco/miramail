package com.ospfranco.trustmail

import com.ospfranco.trustmail.ui.HeaderCollapse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHeaderTest {
    /** Shrinks once scrolled into a long email, comes back near the top, and never flickers in between. */
    @Test
    fun headerCollapsesOnScroll() {
        assertFalse("a small scroll keeps the full header", HeaderCollapse.collapsed(false, 30f, 1000f))
        assertTrue(HeaderCollapse.collapsed(false, 60f, 1000f))
        assertFalse("a short email keeps the full header", HeaderCollapse.collapsed(false, 60f, 200f))
        assertTrue("between the two offsets nothing changes", HeaderCollapse.collapsed(true, 30f, 1000f))
        assertFalse(HeaderCollapse.collapsed(true, 5f, 1000f))
        assertFalse("pulling past the top", HeaderCollapse.collapsed(true, -20f, 1000f))
    }
}
