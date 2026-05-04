package com.nexio.tv.core.image

import android.net.Uri
import coil.decode.DataSource
import coil.fetch.SourceResult
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NexioArtworkFetcherTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `factory accepts asset uri model`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = Uri.parse("nexio-artwork://asset/artwork-asset:RPDB:poster:imdb:tt0137523"),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertTrue(fetcher is NexioArtworkFetcher)
    }

    @Test
    fun `factory rejects remote provider uri model`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = Uri.parse("https://image.tmdb.org/t/p/w500/abc.jpg"),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertNull(fetcher)
    }

    @Test
    fun `factory rejects insecure remote provider uri model`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = Uri.parse("http://image.tmdb.org/t/p/w500/abc.jpg"),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertNull(fetcher)
    }

    @Test
    fun `fetcher reads existing asset file from repository`() = runTest {
        val assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:imdb:tt0137523")
        val file = temp.newFile("asset.bin")
        file.writeBytes("image-bytes".toByteArray())
        val repository = mockk<ArtworkAssetRepository>()
        every { repository.getExistingFile(assetKey) } returns file
        val fetcher = NexioArtworkFetcher(
            assetKey = assetKey,
            repository = repository
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        assertEquals(DataSource.DISK, (result as SourceResult).dataSource)
    }
}
