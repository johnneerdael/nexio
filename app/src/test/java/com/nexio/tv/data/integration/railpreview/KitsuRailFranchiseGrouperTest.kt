package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailRankingMetadata
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class KitsuRailFranchiseGrouperTest {

    @Test
    fun `three MHA seasonal records sharing tvdb are collapsed to first occurrence`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074"),
            series("12268", tvdb = "305074"),
            series("13881", tvdb = "305074"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469"),
            preview("kitsu:12268", kitsuId = "12268"),
            preview("kitsu:13881", kitsuId = "13881"),
        )

        val result = grouper.group(items)

        assertEquals(1, result.size)
        assertEquals("kitsu:11469", result.single().sourceItemId)
    }

    @Test
    fun `grouped representative stableIds are enriched with shared tvdb and imdb`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074", imdb = "tt5626028"),
            series("12268", tvdb = "305074", imdb = "tt5626028"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469"),
            preview("kitsu:12268", kitsuId = "12268"),
        )

        val result = grouper.group(items)

        assertEquals("305074", result.single().stableIds.tvdb)
        assertEquals("tt5626028", result.single().stableIds.imdb)
        assertEquals("11469", result.single().stableIds.kitsu)
    }

    @Test
    fun `movies sharing tvdb with series are NOT grouped into the series card`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074"),
            AnimeIdMapRecord(kitsu = "14084", tvdb = "305074", imdb = "tt7745068", mediaType = "movie", sourceType = "MOVIE"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469", type = ContentType.SERIES),
            preview("kitsu:14084", kitsuId = "14084", type = ContentType.MOVIE),
        )

        val result = grouper.group(items)

        assertEquals(2, result.size)
        assertEquals(listOf("kitsu:11469", "kitsu:14084"), result.map { it.sourceItemId })
    }

    @Test
    fun `OVA sharing tvdb is not grouped into series card`() {
        val grouper = grouperWith(
            series("11469", tvdb = "305074"),
            AnimeIdMapRecord(kitsu = "99999", tvdb = "305074", mediaType = "series", sourceType = "OVA"),
        )
        val items = listOf(
            preview("kitsu:11469", kitsuId = "11469"),
            preview("kitsu:99999", kitsuId = "99999"),
        )

        val result = grouper.group(items)

        assertEquals(2, result.size)
    }

    @Test
    fun `item with no mapping record passes through unchanged`() {
        val grouper = grouperWith()
        val item = preview("kitsu:99999", kitsuId = "99999")

        val result = grouper.group(listOf(item))

        assertEquals(1, result.size)
        assertEquals(item, result.single())
    }

    @Test
    fun `item with kitsu-only record (no tvdb imdb tmdb) passes through unchanged`() {
        val grouper = grouperWith(
            AnimeIdMapRecord(kitsu = "99999", mediaType = "series")
        )
        val item = preview("kitsu:99999", kitsuId = "99999")

        val result = grouper.group(listOf(item))

        assertEquals(1, result.size)
        assertEquals(item, result.single())
    }

    @Test
    fun `unique series preserves order when no grouping occurs`() {
        val grouper = grouperWith(
            series("1", tvdb = "11111"),
            series("2", tvdb = "22222"),
            series("3", tvdb = "33333"),
        )
        val items = listOf(
            preview("kitsu:1", kitsuId = "1"),
            preview("kitsu:2", kitsuId = "2"),
            preview("kitsu:3", kitsuId = "3"),
        )

        val result = grouper.group(items)

        assertEquals(listOf("kitsu:1", "kitsu:2", "kitsu:3"), result.map { it.sourceItemId })
    }

    @Test
    fun `mixed list of grouped and ungrouped preserves position of first grouped occurrence`() {
        val grouper = grouperWith(
            series("1", tvdb = "11111"),
            series("2", tvdb = "22222"),
            series("3", tvdb = "22222"),
        )
        val items = listOf(
            preview("kitsu:1", kitsuId = "1"),
            preview("kitsu:2", kitsuId = "2"),
            preview("kitsu:3", kitsuId = "3"),
        )

        val result = grouper.group(items)

        // item 1 passes through, item 2 is the representative of its group, item 3 is dropped
        assertEquals(2, result.size)
        assertEquals(listOf("kitsu:1", "kitsu:2"), result.map { it.sourceItemId })
    }

    // --- helpers ---

    private fun grouperWith(vararg records: AnimeIdMapRecord): KitsuRailFranchiseGrouper {
        val asset = AnimeIdMapAsset(
            schemaVersion = 1,
            recordsByKitsu = records.associateBy { it.kitsu }
        )
        return KitsuRailFranchiseGrouper(AnimeIdMappingService(assetProvider = { asset }))
    }

    private fun series(kitsu: String, tvdb: String, imdb: String = "") =
        AnimeIdMapRecord(kitsu = kitsu, tvdb = tvdb, imdb = imdb.takeIf { it.isNotEmpty() }, mediaType = "series", sourceType = "TV")

    private fun preview(
        sourceItemId: String,
        kitsuId: String,
        type: ContentType = ContentType.SERIES,
    ) = RailItemPreview(
        railId = "kitsu_trending_anime",
        railSource = RailSource.BUILT_IN_KITSU,
        sourceProvider = ProviderId.KITSU,
        sourceItemId = sourceItemId,
        itemType = type,
        stableIds = ProviderIds(kitsu = kitsuId),
        display = RailDisplaySeed(title = "Anime $kitsuId"),
        ranking = RailRankingMetadata(rank = 1),
        sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
        sourcePayloadHash = "hash-$kitsuId",
        generatedAtMs = 1000L,
    )
}
