package com.ospfranco.trustmail

import com.ospfranco.trustmail.security.PublicSuffixList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PublicSuffixListTest {
    @get:Rule val psl = PublicSuffixRule()

    /** Expected values were produced by `tldts.getDomain()`, which the Expo app used. */
    @Test
    fun matchesTldtsReferenceTable() {
        val lines = Fixture.text("domains.jsonl").lines()
        assertTrue(lines.size > 20)
        for (line in lines) {
            val row = Json.parseToJsonElement(line).jsonArray
            val host = row[0].jsonPrimitive.content
            val expected = if (row[1] is JsonNull) null else row[1].jsonPrimitive.content
            assertEquals("host: $host", expected, PublicSuffixList.shared.registrableDomain(host))
        }
    }

    @Test
    fun parsesRuleKinds() {
        val list = PublicSuffixList("com\nco.uk\n*.ck\n!www.ck\n")
        assertEquals("example.co.uk", list.registrableDomain("a.b.example.co.uk"))
        assertNull(list.registrableDomain("co.uk"))
        assertNull(list.registrableDomain("foo.ck"))
        assertEquals("bar.foo.ck", list.registrableDomain("bar.foo.ck"))
        assertEquals("www.ck", list.registrableDomain("x.www.ck"))
        assertNull(list.registrableDomain(""))
    }
}
