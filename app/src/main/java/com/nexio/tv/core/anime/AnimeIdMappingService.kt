package com.nexio.tv.core.anime

import android.content.Context
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

    private companion object {
        private val EMPTY_ASSET: AnimeIdMapAsset = AnimeIdMapAsset(schemaVersion = 0)
    }
}
