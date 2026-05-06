package com.nexio.tv.core.anime

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeIdMappingServiceImdbTest {

    private fun service(): AnimeIdMappingService {
        val json = javaClass.getResource("/fixtures/nexio-anime-map-v1-test.json")!!.readText()
        val asset = AnimeIdMapAssetJsonAdapter(Moshi.Builder().build()).fromJson(json)!!
        return AnimeIdMappingService(assetProvider = { asset })
    }

    @Test fun `byImdb groups all MHA Kitsu ids that share tt5626028`() {
        val records = service().recordsForImdbId("tt5626028")
        val kitsuIds = records.map { it.kitsu }.toSet()
        assertTrue("MHA S1 must be present", "11469" in kitsuIds)
        assertTrue("MHA S3 must be present", "13881" in kitsuIds)
    }

    @Test fun `imdb anime detection works with multiple Kitsu candidates`() {
        assertTrue(service().isAnimeImdbId("tt5626028"))
        assertTrue(service().isAnimeImdbId("tt0388629"))
    }

    @Test fun `imdb to kitsu does not silently choose season 1 when multiple records exist`() {
        // recordsForImdbId returns the full list — caller must not just take first()
        val records = service().recordsForImdbId("tt5626028")
        assertTrue("must return all members, not just one", records.size >= 2)
    }

    @Test fun `unmapped imdb id returns empty list`() {
        assertEquals(emptyList<AnimeIdMapRecord>(), service().recordsForImdbId("tt9999999"))
    }
}
