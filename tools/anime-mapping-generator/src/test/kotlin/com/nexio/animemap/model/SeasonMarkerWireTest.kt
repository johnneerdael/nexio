package com.nexio.animemap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonMarkerWireTest {

    @Test fun `numeric season marker round-trips`() {
        val wire = SeasonMarkerWire.normalize("3")
        assertEquals("3", wire)
    }

    @Test fun `absolute marker normalizes to lowercase a`() {
        assertEquals("a", SeasonMarkerWire.normalize("a"))
        assertEquals("a", SeasonMarkerWire.normalize("A"))
    }

    @Test fun `hentai marker normalizes lowercase`() {
        assertEquals("hentai", SeasonMarkerWire.normalize("hentai"))
        assertEquals("hentai", SeasonMarkerWire.normalize("Hentai"))
    }

    @Test fun `unknown marker normalizes lowercase`() {
        assertEquals("unknown", SeasonMarkerWire.normalize("unknown"))
        assertEquals("unknown", SeasonMarkerWire.normalize("UNKNOWN"))
    }

    @Test fun `empty string returns null`() {
        assertNull(SeasonMarkerWire.normalize(""))
        assertNull(SeasonMarkerWire.normalize("   "))
    }

    @Test fun `null input returns null`() {
        assertNull(SeasonMarkerWire.normalize(null))
    }

    @Test fun `unrecognized non-numeric returns null`() {
        assertNull(SeasonMarkerWire.normalize("xyz"))
    }

    @Test fun `numeric value with surrounding whitespace is normalized`() {
        assertEquals("7", SeasonMarkerWire.normalize(" 7 "))
    }
}
