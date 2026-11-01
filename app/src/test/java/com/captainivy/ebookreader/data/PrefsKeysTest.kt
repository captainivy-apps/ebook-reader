package com.captainivy.ebookreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsKeysTest {

    @Test
    fun decode_null_returnsDefaults() {
        val defaults = listOf(21, 20)
        assertEquals(defaults, Prefs.decodeKeys(null, defaults))
    }

    @Test
    fun decode_blank_returnsEmpty() {
        val defaults = listOf(21, 20)
        assertTrue(Prefs.decodeKeys("", defaults).isEmpty())
        assertTrue(Prefs.decodeKeys("   ", defaults).isEmpty())
    }

    @Test
    fun encode_joinsWithComma() {
        assertEquals("21,20,62", Prefs.encodeKeys(listOf(21, 20, 62)))
        assertEquals("", Prefs.encodeKeys(emptyList()))
    }

    @Test
    fun decode_parsesList() {
        assertEquals(listOf(21, 20, 62), Prefs.decodeKeys("21,20,62", emptyList()))
    }

    @Test
    fun decode_ignoresInvalidAndDuplicates() {
        assertEquals(listOf(21, 20), Prefs.decodeKeys("21, x, 20, 21, ,", emptyList()))
    }

    @Test
    fun encodeDecode_roundTrip() {
        val keys = listOf(22, 20, 19)
        assertEquals(keys, Prefs.decodeKeys(Prefs.encodeKeys(keys), emptyList()))
    }

    @Test
    fun encodeDecode_emptyRoundTrip() {
        assertTrue(Prefs.decodeKeys(Prefs.encodeKeys(emptyList()), listOf(1)).isEmpty())
    }
}
