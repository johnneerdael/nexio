package com.nexio.tv.core.image

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

class NexioArtworkFetcherTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `factory accepts asset uri`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = "nexio-artwork://asset/artwork-asset:RPDB:poster:imdb:tt0137523",
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertTrue(fetcher is NexioArtworkFetcher)
    }

    @Test
    fun `factory rejects remote provider url`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = "https://image.tmdb.org/t/p/w500/abc.jpg",
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertNull(fetcher)
    }

    @Test
    fun `factory rejects insecure remote provider url`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = "http://image.tmdb.org/t/p/w500/abc.jpg",
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
            options = mockk(relaxed = true),
            repository = repository
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        assertEquals(DataSource.DISK, (result as SourceResult).dataSource)
    }
}
