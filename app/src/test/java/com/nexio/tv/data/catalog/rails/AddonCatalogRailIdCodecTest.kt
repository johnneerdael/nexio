package com.nexio.tv.data.catalog.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AddonCatalogRailIdCodecTest {

    private val codec = AddonCatalogRailIdCodec()

    @Test
    fun `encode produces stable triple-delimited string`() {
        assertEquals(
            "addon::com.example.addon::movie::top",
            codec.encode(addonId = "com.example.addon", type = "movie", catalogId = "top")
        )
    }

    @Test
    fun `decode round-trips`() {
        val railId = codec.encode(addonId = "com.example.addon", type = "series", catalogId = "trending")
        val parts = codec.decode(railId)
        assertEquals(AddonCatalogRailIdCodec.Parts(addonId = "com.example.addon", type = "series", catalogId = "trending"), parts)
    }

    @Test
    fun `decode returns null for prefix mismatch`() {
        assertNull(codec.decode("trakt::foo::movie::trending"))
    }

    @Test
    fun `decode returns null for too few parts`() {
        assertNull(codec.decode("addon::onlyone"))
        assertNull(codec.decode("addon::a::b"))
    }

    @Test
    fun `decode returns null for blank inputs`() {
        assertNull(codec.decode(""))
        assertNull(codec.decode("   "))
    }

    @Test
    fun `encode rejects blank fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(addonId = "", type = "movie", catalogId = "top")
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(addonId = "x", type = "", catalogId = "top")
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(addonId = "x", type = "movie", catalogId = "")
        }
    }

    @Test
    fun `encode rejects fields containing the delimiter`() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(addonId = "ad::on", type = "movie", catalogId = "top")
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(addonId = "addon", type = "mo::vie", catalogId = "top")
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(addonId = "addon", type = "movie", catalogId = "to::p")
        }
    }
}
