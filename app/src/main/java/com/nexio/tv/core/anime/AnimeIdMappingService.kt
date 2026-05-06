package com.nexio.tv.core.anime

import android.content.Context
import com.nexio.tv.domain.model.ProviderIds
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val ANIME_ID_MAP_ASSET = "anime/nexio-anime-map-v1.json"

@Singleton
class AnimeIdMappingService(
    private val assetProvider: () -> AnimeIdMapAsset
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        moshi: Moshi
    ) : this(
        assetProvider = {
            val adapter = moshi.adapter(AnimeIdMapAsset::class.java)
            context.assets.open(ANIME_ID_MAP_ASSET).bufferedReader().use { reader ->
                requireNotNull(adapter.fromJson(reader.readText())) {
                    "Unable to parse anime ID map asset"
                }
            }
        }
    )

    private val asset: AnimeIdMapAsset by lazy {
        runCatching { assetProvider() }
            .onFailure { error ->
                android.util.Log.w(
                    "AnimeIdMappingService",
                    "anime-id-map asset unavailable; degrading to empty resolution",
                    error
                )
            }
            .getOrDefault(EMPTY_ASSET)
    }

    fun resolveKitsuId(id: AnimeStremioId, mediaKind: ContentMediaKind): String? {
        val indexes = asset.indexes
        return when (id.source) {
            AnimeIdSource.KITSU -> id.value.takeIf { indexes.byKitsu.containsKey(it) } ?: id.value
            AnimeIdSource.MAL -> indexes.byMal[id.value]
            AnimeIdSource.ANILIST -> indexes.byAnilist[id.value]
            AnimeIdSource.ANIDB -> indexes.byAnidb[id.value]
            AnimeIdSource.TVDB -> indexes.byTvdb[id.value]?.firstOrNull()
            AnimeIdSource.IMDB -> resolveImdbKitsuId(id.value, mediaKind)
            AnimeIdSource.TMDB -> when (mediaKind) {
                ContentMediaKind.MOVIE -> indexes.byTmdbMovie[id.value]
                ContentMediaKind.SERIES -> indexes.byTmdbTv[id.value]?.firstOrNull()
            }
        }
    }

    private fun resolveImdbKitsuId(imdbId: String, mediaKind: ContentMediaKind): String? {
        val candidates = asset.indexes.byImdb[imdbId] ?: return null
        if (candidates.isEmpty()) return null
        val records = candidates.mapNotNull { asset.identityRecordsByKitsu[it] }
        val matched = records.firstOrNull { rec -> rec.matches(mediaKind) }
        return matched?.kitsu ?: candidates.firstOrNull()
    }

    fun resolveProviderIdsForKitsu(kitsuId: String, mediaKind: ContentMediaKind): ProviderIds {
        val cleanKitsuId = kitsuId
            .trim()
            .removePrefix("kitsu:")
            .takeIf { it.isNotBlank() }
            ?: return ProviderIds()
        val record = asset.identityRecordsByKitsu[cleanKitsuId]
            ?.takeIf { it.matches(mediaKind) }
            ?: return ProviderIds(kitsu = cleanKitsuId)

        return ProviderIds(
            imdb = record.imdb,
            tmdb = record.tmdb,
            tvdb = record.tvdb,
            kitsu = record.kitsu,
            mal = record.mal,
            anilist = record.anilist,
            anidb = record.anidb
        )
    }

    fun recordForKitsuId(kitsuId: String): AnimeIdMapRecord? =
        asset.identityRecordsByKitsu[kitsuId.removePrefix("kitsu:")]

    fun recordForAnidbId(anidbId: String): AnimeIdMapRecord? {
        val kitsu = asset.indexes.byAnidb[anidbId] ?: return null
        return asset.identityRecordsByKitsu[kitsu]
    }

    fun episodeMappingForAnidb(anidbId: String): AnimeEpisodeMappingRecord? =
        asset.episodeMappingsByAnidb[anidbId]

    fun recordsForImdbId(imdbId: String): List<AnimeIdMapRecord> {
        val kitsuIds = asset.indexes.byImdb[imdbId] ?: return emptyList()
        return kitsuIds.mapNotNull { asset.identityRecordsByKitsu[it] }
    }

    fun isAnimeImdbId(imdbId: String): Boolean = recordsForImdbId(imdbId).isNotEmpty()

    /**
     * Returns every SERIES TV record sharing the same tvdb id as [record].
     * Excludes movies, OVAs, ONAs, specials, and music — they share TVDB ids
     * with their parent series in the asset but must NOT be grouped into the
     * series work identity.
     */
    fun allSeriesRecordsSharingTvdb(record: AnimeIdMapRecord): List<AnimeIdMapRecord> {
        val tvdb = record.tvdb?.takeIf { it.isNotBlank() } ?: return listOf(record)
        val kitsuIds = asset.indexes.byTvdb[tvdb]
        val candidates = if (!kitsuIds.isNullOrEmpty()) {
            kitsuIds.mapNotNull { asset.identityRecordsByKitsu[it] }
        } else {
            asset.identityRecordsByKitsu.values.filter { it.tvdb == tvdb }
        }
        val filtered = candidates.filter { isSeriesTvEntry(it) }
        return filtered.ifEmpty { listOf(record) }
    }

    private fun isSeriesTvEntry(record: AnimeIdMapRecord): Boolean {
        val mediaType = record.mediaType?.lowercase() ?: return true
        val sourceType = record.sourceType?.lowercase() ?: ""
        return mediaType == "series" && sourceType in setOf("tv", "")
    }

    private fun AnimeIdMapRecord.matches(mediaKind: ContentMediaKind): Boolean {
        val type = mediaType?.trim()?.lowercase() ?: return true
        return when (mediaKind) {
            ContentMediaKind.MOVIE -> type == "movie"
            ContentMediaKind.SERIES -> type != "movie"
        }
    }

    private companion object {
        private val EMPTY_ASSET: AnimeIdMapAsset = AnimeIdMapAsset(schemaVersion = 0)
    }
}
