package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.MailLabel
import com.ospfranco.trustmail.security.LinkFindingLabel
import com.ospfranco.trustmail.security.SenderCheckLabel
import com.ospfranco.trustmail.ui.MessageAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strings come from scripts/strings.json; these check what the apps got from it (twin of LocalizationTests on iOS). */
class LocalizationTest {
    private val placeholder = Regex("""%\d+\${'$'}s""")

    @Test
    fun everyStringIsTranslatedWithTheSamePlaceholders() {
        val english = TestStrings.english.byName - "app_name"
        val german = TestStrings.german.byName
        assertEquals(english.keys, german.keys)
        for ((name, text) in english) {
            assertTrue("$name is empty in German", german.getValue(name).isNotBlank())
            assertEquals(name, placeholder.findAll(text).map { it.value }.toSet(), placeholder.findAll(german.getValue(name)).map { it.value }.toSet())
        }
    }

    @Test
    fun theSecurityCardsSpeakGerman() {
        val german = TestStrings.german
        assertEquals(
            listOf(
                "Absender bestätigt",
                "Absender nicht bestätigt",
                "Absenderprüfung fehlgeschlagen",
                "Bekannter Absender",
                "Erste E-Mail von diesem Absender",
                "Absenderverlauf unbekannt",
            ),
            SenderCheckLabel.entries.map { german.get(it.title) },
        )
        assertEquals(listOf("Unsichere Links", "Verdächtige Links"), LinkFindingLabel.entries.map { german.get(it.title) })
        assertEquals(
            "Du hast mit dieser Adresse schon E-Mails ausgetauscht.",
            german.get(SenderCheckLabel.Known.description),
        )
    }

    @Test
    fun noticesFillInTheirPlaceholderInGerman() {
        assertEquals("Nach Work verschoben", MessageAction.Move(MailLabel("w", "Work")).notice(TestStrings.german))
        assertEquals("Archiviert", MessageAction.Archive.notice(TestStrings.german))
    }
}
