package com.nexio.tv.ui.components

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailCardDataTest {

    private fun makeRef(value: String) = ArtworkDisplayRef.LegacyString(
        value = value,
        imageType = ArtworkType.POSTER,
        trace = ArtworkTrace.empty()
    )

    private val sample = object : RailCardData {
        override val id: String = "tt12345"
        override val name: String? = "Some Title"
        override val posterRef: ArtworkDisplayRef? = makeRef("https://example.com/poster.jpg")
        override val posterProviderTag: String? = "tmdb"
    }

    @Test
    fun `interface exposes id`() {
        assertEquals("tt12345", sample.id)
    }

    @Test
    fun `interface exposes name as nullable`() {
        assertEquals("Some Title", sample.name)
    }

    @Test
    fun `interface exposes posterRef as nullable ArtworkDisplayRef`() {
        val ref = sample.posterRef
        assertEquals(ArtworkType.POSTER, ref?.imageType)
    }

    @Test
    fun `interface exposes posterProviderTag as nullable`() {
        assertEquals("tmdb", sample.posterProviderTag)
    }

    @Test
    fun `null fields are permitted`() {
        val empty = object : RailCardData {
            override val id: String = "x"
            override val name: String? = null
            override val posterRef: ArtworkDisplayRef? = null
            override val posterProviderTag: String? = null
        }
        assertNull(empty.name)
        assertNull(empty.posterRef)
        assertNull(empty.posterProviderTag)
    }
}
