package com.nexio.tv.core.anime

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val ANIME_ID_MAP_ASSET = "anime/anime-id-map.json"
private const val TAG = "AnimeIdMappingService"

private val EMPTY_ANIME_ID_MAP_ASSET = AnimeIdMapAsset(schemaVersion = 0)

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
            try {
                val adapter = moshi.adapter(AnimeIdMapAsset::class.java)
                val parsed = context.assets.open(ANIME_ID_MAP_ASSET).bufferedReader().use { reader ->
                    adapter.fromJson(reader.readText())
                }
                if (parsed != null) {
                    parsed
                } else {
                    Log.w(TAG, "Anime ID map parsed to null; anime metadata will be unavailable")
                    EMPTY_ANIME_ID_MAP_ASSET
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load $ANIME_ID_MAP_ASSET; anime metadata will be unavailable", t)
                EMPTY_ANIME_ID_MAP_ASSET
            }
        }
    )

    private val asset: AnimeIdMapAsset by lazy { assetProvider() }

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
}
