package com.nexio.tv.core.anime

import android.content.Context
import com.nexio.tv.domain.model.ProviderIds
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val ANIME_ID_MAP_ASSET = "anime/anime-id-map.json"

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
        return when (id.source) {
            AnimeIdSource.KITSU -> id.value.takeIf { asset.byKitsu.containsKey(it) } ?: id.value
            AnimeIdSource.MAL -> asset.byMal[id.value]
            AnimeIdSource.ANILIST -> asset.byAnilist[id.value]
            AnimeIdSource.ANIDB -> asset.byAnidb[id.value]
            AnimeIdSource.TVDB -> asset.byTvdb[id.value]
            AnimeIdSource.IMDB -> asset.byImdb[id.value]
            AnimeIdSource.TMDB -> when (mediaKind) {
                ContentMediaKind.MOVIE -> asset.byTmdbMovie[id.value]
                ContentMediaKind.SERIES -> asset.byTmdbSeries[id.value]
            }
        }
    }

    fun resolveProviderIdsForKitsu(kitsuId: String, mediaKind: ContentMediaKind): ProviderIds {
        val cleanKitsuId = kitsuId
            .trim()
            .removePrefix("kitsu:")
            .takeIf { it.isNotBlank() }
            ?: return ProviderIds()
        val record = asset.recordsByKitsu[cleanKitsuId]
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
        asset.recordsByKitsu[kitsuId.removePrefix("kitsu:")]

    /**
     * Returns every SERIES TV record sharing the same tvdb id as [record].
     * Excludes movies, OVAs, ONAs, specials, and music — they share TVDB ids
     * with their parent series in the asset but must NOT be grouped into the
     * series work identity.
     */
    fun allSeriesRecordsSharingTvdb(record: AnimeIdMapRecord): List<AnimeIdMapRecord> {
        val tvdb = record.tvdb?.takeIf { it.isNotBlank() } ?: return listOf(record)
        return asset.recordsByKitsu.values.filter { other ->
            other.tvdb == tvdb && isSeriesTvEntry(other)
        }
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
