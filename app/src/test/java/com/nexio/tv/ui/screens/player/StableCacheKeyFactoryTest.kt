package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class StableCacheKeyFactoryTest {

    private val factory = StableCacheKeyFactory()

    private fun dataSpec(url: String, length: Long = 1_000_000L): DataSpec =
        DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setLength(length)
            .build()

    @Test
    fun `two different RealDebrid CDN URLs for the same torrent hash produce identical cache key`() {
        val fileSize = 1_500_000_000L
        val key1 = factory.buildCacheKey(
            dataSpec("https://download1.real-debrid.com/d/ABC123HASH/movie.mkv", fileSize)
        )
        val key2 = factory.buildCacheKey(
            dataSpec("https://download2.real-debrid.com/d/ABC123HASH/movie.mkv", fileSize)
        )

        assertEquals("nexio:v1:ABC123HASH:$fileSize", key1)
        assertEquals(key1, key2)
    }

    @Test
    fun `two different torrents produce different cache keys`() {
        val fileSize = 1_500_000_000L
        val key1 = factory.buildCacheKey(
            dataSpec("https://download1.real-debrid.com/d/ABC123HASH/movie.mkv", fileSize)
        )
        val key2 = factory.buildCacheKey(
            dataSpec("https://download1.real-debrid.com/d/XYZ789HASH/movie.mkv", fileSize)
        )

        assertNotEquals(key1, key2)
        assertEquals("nexio:v1:ABC123HASH:$fileSize", key1)
        assertEquals("nexio:v1:XYZ789HASH:$fileSize", key2)
    }

    @Test
    fun `fallback path normalization strips query params`() {
        val key = factory.buildCacheKey(
            dataSpec("https://unknown-cdn.com/path/to/file.mkv?token=abc&expires=123", 1_000_000L)
        )

        assertEquals("nexio:v1:url:/path/to/file.mkv", key)
    }

    @Test
    fun `AllDebrid URL pattern extracts ID correctly`() {
        val fileSize = 2_000_000_000L
        val key = factory.buildCacheKey(
            dataSpec("https://server1.alldebrid.com/dl/ALLDEBRID_ID_123/movie.mkv", fileSize)
        )

        assertEquals("nexio:v1:ALLDEBRID_ID_123:$fileSize", key)
    }

    @Test
    fun `when fileSize is LENGTH_UNSET falls back to URL-based key even if identity is extractable`() {
        val key = factory.buildCacheKey(
            dataSpec(
                "https://download1.real-debrid.com/d/ABC123HASH/movie.mkv",
                C.LENGTH_UNSET.toLong()
            )
        )

        assertEquals("nexio:v1:url:/d/ABC123HASH/movie.mkv", key)
    }

    @Test
    fun `unknown CDN host falls back to normalizedPathKey`() {
        val key = factory.buildCacheKey(
            dataSpec("https://someunknowncdn.example.com/files/video.mkv", 500_000_000L)
        )

        assertEquals("nexio:v1:url:/files/video.mkv", key)
    }
}
