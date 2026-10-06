package com.ospfranco.trustmail

import com.ospfranco.trustmail.mail.SenderAvatar
import com.ospfranco.trustmail.mail.senderName
import org.junit.Assert.assertEquals
import org.junit.Test

class SenderTest {
    @Test
    fun namesTheSender() {
        assertEquals("Ann", senderName("Ann <ANN@Example.com>"))
        assertEquals("Smith, Ann", senderName("\"Smith, Ann\" <ann@example.com>"))
        assertEquals("ann@example.com", senderName("<ann@example.com>"))
        assertEquals("ann@example.com", senderName("Ann@Example.com"))
    }

    @Test
    fun avatarShowsTheInitialOnAColorTiedToTheAddress() {
        assertEquals(SenderAvatar.of("ann@example.com"), SenderAvatar.of("Ann <ANN@Example.com>"))
        assertEquals("A", SenderAvatar.of("Ann <ann@example.com>").initial)
        assertEquals("3949AB", SenderAvatar.of("Ann <ann@example.com>").hexColor)
        assertEquals("43A047", SenderAvatar.of("GitHub <noreply@github.com>").hexColor)
        assertEquals("É", SenderAvatar.of("émile <émile@example.fr>").initial)
        assertEquals("F4511E", SenderAvatar.of("émile <émile@example.fr>").hexColor)
        assertEquals("?", SenderAvatar.of("<...@...>").initial)
    }
}
