package com.nexio.tv.core.image

import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRemoteArtworkModelTest {
    @Test
    fun `legacy remote artwork model rejects raw premium provider urls`() {
        assertNull(
            "https://api.ratingposterdb.com/secret/imdb/poster-default/tt15940132.jpg"
                .toLegacyArtworkCoilModelOrNull("movie:poster", ArtworkType.POSTER)
        )
        assertNull(
            "https://api.top-posters.com/secret/imdb/poster/tt15940132.jpg"
                .toLegacyArtworkCoilModelOrNull("movie:poster", ArtworkType.POSTER)
        )
    }

    @Test
    fun `legacy remote artwork model still accepts normal provider fallbacks`() {
        val model = "https://image.tmdb.org/t/p/w500/poster.jpg?token=secret"
            .toLegacyArtworkCoilModelOrNull("movie:poster", ArtworkType.POSTER)

        assertTrue(model is LegacyRemoteArtworkModel)
        assertTrue(model.toString().startsWith("legacy-artwork:poster:movie:poster:"))
    }
}
