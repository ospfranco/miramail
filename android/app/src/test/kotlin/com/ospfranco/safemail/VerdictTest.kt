package com.ospfranco.safemail

import com.ospfranco.safemail.security.LinkCheckKind
import com.ospfranco.safemail.security.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    @Test
    fun worstVerdictWins() {
        assertEquals(Verdict.Safe, Verdict.worst(emptyList()))
        assertEquals(Verdict.Safe, Verdict.worst(listOf(Verdict.Safe, Verdict.Safe)))
        assertEquals(Verdict.Warning, Verdict.worst(listOf(Verdict.Safe, Verdict.Warning, Verdict.Safe)))
        assertEquals(Verdict.Danger, Verdict.worst(listOf(Verdict.Warning, Verdict.Danger, Verdict.Safe)))
    }

    @Test
    fun glyphsAndColors() {
        assertEquals("✓", Verdict.Safe.glyph)
        assertEquals("!", Verdict.Warning.glyph)
        assertEquals("✕", Verdict.Danger.glyph)
        assertEquals("E5484D", Verdict.Danger.hexColor)
        assertEquals("danger", Verdict.Danger.id)
    }

    @Test
    fun everyCheckKindHasALabel() {
        for (kind in LinkCheckKind.entries) assertTrue(kind.label.isNotEmpty())
        assertEquals("DNS", LinkCheckKind.DnsSpoofing.label)
    }
}
