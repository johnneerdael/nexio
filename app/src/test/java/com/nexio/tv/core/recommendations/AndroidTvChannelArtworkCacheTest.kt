package com.nexio.tv.core.recommendations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvChannelArtworkCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `request returns provider specific cache key for poster`() {
        val request = AndroidTvChannelArtworkCache.posterRequest(
            context = context,
            item = preview(
                id = "tt15940132",
                poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt15940132.jpg",
                posterProviderTag = "rpdb"
            )
        )

        requireNotNull(request)
        assertEquals(
            ArtworkImageCacheKeys.poster(
                itemId = "tt15940132",
                providerTag = "rpdb",
                posterUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt15940132.jpg"
            ),
            request.diskCacheKey
        )
        assertEquals(
            AndroidTvChannelArtwork.posterUri(context, request.diskCacheKey),
            request.localUri
        )
    }

    @Test
    fun `request returns null when item has no poster`() {
        assertNull(AndroidTvChannelArtworkCache.posterRequest(context, preview(poster = null)))
        assertNull(AndroidTvChannelArtworkCache.posterRequest(context, preview(poster = " ")))
    }

    @Test
    fun `prune removes unreferenced channel artwork files`() {
        val keepRequest = requireNotNull(
            AndroidTvChannelArtworkCache.posterRequest(
                context,
                preview(id = "tt1", poster = "https://images.example/keep.jpg")
            )
        )
        val removeRequest = requireNotNull(
            AndroidTvChannelArtworkCache.posterRequest(
                context,
                preview(id = "tt2", poster = "https://images.example/remove.jpg")
            )
        )
        val keepFile = AndroidTvChannelArtwork.posterFile(context, keepRequest.diskCacheKey)
        val removeFile = AndroidTvChannelArtwork.posterFile(context, removeRequest.diskCacheKey)
        keepFile.parentFile?.mkdirs()
        keepFile.writeBytes(byteArrayOf(1))
        removeFile.writeBytes(byteArrayOf(2))

        AndroidTvChannelArtworkCache.pruneUnreferencedFiles(
            context = context,
            activeDiskCacheKeys = setOf(keepRequest.diskCacheKey)
        )

        assertTrue(keepFile.exists())
        assertEquals(false, removeFile.exists())
    }

    private fun preview(
        id: String = "tt123",
        poster: String? = "https://images.example/poster.jpg",
        posterProviderTag: String? = null
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = "Example",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            posterProviderTag = posterProviderTag
        )
    }
}
