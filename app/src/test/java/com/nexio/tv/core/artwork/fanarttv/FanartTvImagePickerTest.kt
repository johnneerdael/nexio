package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FanartTvImagePickerTest {
    private val picker = FanartTvImagePicker()

    @Test fun `movie logo picks highest-likes en, ignores higher non-en`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = "es.png", lang = "es", likes = "20"),
            FanartTvImage(id = "2", url = "en-hi.png", lang = "en", likes = "8"),
            FanartTvImage(id = "3", url = "en-low.png", lang = "en", likes = "5")
        ))
        assertEquals("en-hi.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `tv logo picks highest-likes en`() {
        val doc = FanartTvDocument(hdTvLogo = listOf(
            FanartTvImage(id = "1", url = "best.png", lang = "en", likes = "24"),
            FanartTvImage(id = "2", url = "ok.png", lang = "en", likes = "10")
        ))
        assertEquals("best.png", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO))
    }

    @Test fun `movie backdrop highest-likes any-lang`() {
        val doc = FanartTvDocument(movieBackground = listOf(
            FanartTvImage(id = "1", url = "a.jpg", lang = "", likes = "5"),
            FanartTvImage(id = "2", url = "b.jpg", lang = "", likes = "3")
        ))
        assertEquals("a.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP))
    }

    @Test fun `tv backdrop highest-likes any-lang`() {
        val doc = FanartTvDocument(showBackground = listOf(
            FanartTvImage(id = "1", url = "x.jpg", lang = "", likes = "12"),
            FanartTvImage(id = "2", url = "y.jpg", lang = "", likes = "10")
        ))
        assertEquals("x.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP))
    }

    @Test fun `movie poster highest-likes en`() {
        val doc = FanartTvDocument(moviePoster = listOf(
            FanartTvImage(id = "1", url = "ru.jpg", lang = "ru", likes = "100"),
            FanartTvImage(id = "2", url = "en15.jpg", lang = "en", likes = "15"),
            FanartTvImage(id = "3", url = "en13.jpg", lang = "en", likes = "13")
        ))
        assertEquals("en15.jpg", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
    }

    @Test fun `tv poster highest-likes en`() {
        val doc = FanartTvDocument(tvPoster = listOf(
            FanartTvImage(id = "1", url = "en14.jpg", lang = "en", likes = "14"),
            FanartTvImage(id = "2", url = "en6.jpg", lang = "en", likes = "6")
        ))
        assertEquals("en14.jpg", picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER))
    }

    @Test fun `null when no en variant for poster`() {
        val doc = FanartTvDocument(moviePoster = listOf(
            FanartTvImage(id = "1", url = "ru.jpg", lang = "ru", likes = "5")
        ))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
    }

    @Test fun `null when no en variant for logo`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = "ru.png", lang = "ru", likes = "5")
        ))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `null when arrays missing`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER))
    }

    @Test fun `null when array empty`() {
        assertNull(picker.pickFor(FanartTvDocument(hdMovieLogo = emptyList()),
            FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `tie-break by ascending id`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "200", url = "later.png", lang = "en", likes = "8"),
            FanartTvImage(id = "100", url = "earlier.png", lang = "en", likes = "8")
        ))
        assertEquals("earlier.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `malformed likes treated as zero`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = "good.png", lang = "en", likes = "1"),
            FanartTvImage(id = "2", url = "junk.png", lang = "en", likes = "abc")
        ))
        assertEquals("good.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `null url skipped`() {
        val doc = FanartTvDocument(hdMovieLogo = listOf(
            FanartTvImage(id = "1", url = null, lang = "en", likes = "100"),
            FanartTvImage(id = "2", url = "real.png", lang = "en", likes = "1")
        ))
        assertEquals("real.png", picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO))
    }

    @Test fun `thumbnail returns null`() {
        val doc = FanartTvDocument()
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.THUMBNAIL))
        assertNull(picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.THUMBNAIL))
    }

    @Test
    fun `fight club fixture picks expected urls`() {
        val doc = loadFixture("fight-club-550.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-504c0530d5f93.png",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.LOGO)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-55e2393686745.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.BACKDROP)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/fight-club-522a5477c7bd3.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.MOVIE, ArtworkType.POSTER)
        )
    }

    @Test
    fun `breaking bad fixture picks expected urls`() {
        val doc = loadFixture("breaking-bad-81189.json")
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-503d6f03d4bfe.png",
            picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.LOGO)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-4fcb7b24428ba.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.BACKDROP)
        )
        assertEquals(
            "https://assets.fanart.tv/fanart/breaking-bad-5427fc5ebded7.jpg",
            picker.pickFor(doc, FanartTvCallId.Type.TV, ArtworkType.POSTER)
        )
    }

    private fun loadFixture(name: String): FanartTvDocument {
        val text = checkNotNull(this::class.java.getResource("/fixtures/fanarttv/$name")) {
            "Fixture $name not found on test classpath"
        }.readText()
        return kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }.decodeFromString(FanartTvDocument.serializer(), text)
    }
}
