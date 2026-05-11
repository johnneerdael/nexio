package com.nexio.tv.core.anime

import android.content.Context
import com.nexio.tv.core.anime.binary.AnimeIdMapBinaryReader
import com.nexio.tv.core.anime.binary.IndexKind
import com.nexio.tv.domain.model.ProviderIds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-path service: the production (Hilt-injected) path delegates to
 * [AnimeIdMapBinaryReader] which mmaps the v1 binary asset, keeping JVM-heap
 * retention at ~1 KiB regardless of asset size. The legacy [assetProvider]
 * path retains the in-memory [AnimeIdMapAsset] model for back-compat with
 * test fixtures and is NOT used in production.
 *
 * Both paths MUST produce identical results for identical inputs — this is
 * the contract gate that lets the binary swap land without touching the
 * ~12 dependent test files that still construct via inline assets.
 */
@Singleton
class AnimeIdMappingService private constructor(
    private val reader: AnimeIdMapBinaryReader?,
    private val assetProvider: (() -> AnimeIdMapAsset)?,
) {
    @Inject
    constructor(@ApplicationContext context: Context)
        : this(reader = AnimeIdMapBinaryReader(context), assetProvider = null)

    /** Production constructor (also useful for test fakes that pass a stub reader). */
    constructor(reader: AnimeIdMapBinaryReader)
        : this(reader = reader, assetProvider = null)

    /**
     * Legacy test-only constructor accepting an in-memory asset provider.
     * Kept for back-compat with ~12 test files that construct services with
     * inline [AnimeIdMapAsset] instances. Production callers go through the
     * Hilt [@Inject] constructor which routes through the binary reader.
     */
    constructor(assetProvider: () -> AnimeIdMapAsset)
        : this(reader = null, assetProvider = assetProvider)

    private val cachedAsset: AnimeIdMapAsset by lazy {
        val provider = assetProvider ?: return@lazy EMPTY_ASSET
        runCatching { provider() }
            .onFailure { error ->
                android.util.Log.w(
                    "AnimeIdMappingService",
                    "anime-id-map asset unavailable; degrading to empty resolution",
                    error
                )
            }
            .getOrDefault(EMPTY_ASSET)
    }

    /**
     * Triggers off-main warmup. For the binary path, this opens the mmap.
     * For the asset path, this forces the lazy moshi parse.
     */
    fun warmUp() {
        reader?.ensureOpen()
            ?: run { cachedAsset.schemaVersion }
    }

    // ---------------------------------------------------------------------
    // Public API — branches per construction path; outputs must match.
    // ---------------------------------------------------------------------

    fun resolveKitsuId(id: AnimeStremioId, mediaKind: ContentMediaKind): String? {
        reader?.let { return resolveKitsuIdViaReader(it, id, mediaKind) }
        return resolveKitsuIdViaAsset(cachedAsset, id, mediaKind)
    }

    fun resolveProviderIdsForKitsu(kitsuId: String, mediaKind: ContentMediaKind): ProviderIds {
        val cleanKitsuId = kitsuId
            .trim()
            .removePrefix("kitsu:")
            .takeIf { it.isNotBlank() }
            ?: return ProviderIds()
        reader?.let { r ->
            r.ensureOpen()
            val record = r.recordForKitsu(cleanKitsuId)
                ?.takeIf { it.matches(mediaKind) }
                ?: return ProviderIds(kitsu = cleanKitsuId)
            return ProviderIds(
                imdb = record.imdb,
                tmdb = record.tmdb,
                tvdb = record.tvdb,
                kitsu = record.kitsu,
                mal = record.mal,
                anilist = record.anilist,
                anidb = record.anidb,
            )
        }
        val record = cachedAsset.identityRecordsByKitsu[cleanKitsuId]
            ?.takeIf { it.matches(mediaKind) }
            ?: return ProviderIds(kitsu = cleanKitsuId)
        return ProviderIds(
            imdb = record.imdb,
            tmdb = record.tmdb,
            tvdb = record.tvdb,
            kitsu = record.kitsu,
            mal = record.mal,
            anilist = record.anilist,
            anidb = record.anidb,
        )
    }

    fun recordForKitsuId(kitsuId: String): AnimeIdMapRecord? {
        reader?.let {
            it.ensureOpen()
            return it.recordForKitsu(kitsuId)
        }
        return cachedAsset.identityRecordsByKitsu[kitsuId.removePrefix("kitsu:")]
    }

    fun recordForAnidbId(anidbId: String): AnimeIdMapRecord? {
        reader?.let { r ->
            r.ensureOpen()
            val offsets = r.recordOffsetsForMultiKey(IndexKind.BY_ANIDB, anidbId)
            // BY_ANIDB is logically single-valued (one kitsu per anidb).
            if (offsets.isEmpty()) {
                // Fall back to single-index probe in case generator chose KIND_U64_SINGLE.
                val kitsu = r.lookupSingle(IndexKind.BY_ANIDB, anidbId) ?: return null
                return r.recordForKitsu(kitsu)
            }
            return r.recordAt(offsets[0])
        }
        val kitsu = cachedAsset.indexes.byAnidb[anidbId] ?: return null
        return cachedAsset.identityRecordsByKitsu[kitsu]
    }

    fun episodeMappingForAnidb(anidbId: String): AnimeEpisodeMappingRecord? {
        reader?.let {
            it.ensureOpen()
            return it.episodeMappingForAnidb(anidbId)
        }
        return cachedAsset.episodeMappingsByAnidb[anidbId]
    }

    fun recordsForImdbId(imdbId: String): List<AnimeIdMapRecord> {
        reader?.let { r ->
            r.ensureOpen()
            val offsets = r.recordOffsetsForImdb(imdbId)
            if (offsets.isEmpty()) return emptyList()
            val out = ArrayList<AnimeIdMapRecord>(offsets.size)
            for (i in offsets.indices) {
                val rec = r.recordAt(offsets[i]) ?: continue
                out.add(rec)
            }
            return out
        }
        val kitsuIds = cachedAsset.indexes.byImdb[imdbId] ?: return emptyList()
        return kitsuIds.mapNotNull { cachedAsset.identityRecordsByKitsu[it] }
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
        reader?.let { r ->
            r.ensureOpen()
            val offsets = r.recordOffsetsForMultiKey(IndexKind.BY_TVDB, tvdb)
            if (offsets.isEmpty()) return listOf(record)
            val candidates = ArrayList<AnimeIdMapRecord>(offsets.size)
            for (i in offsets.indices) {
                val rec = r.recordAt(offsets[i]) ?: continue
                candidates.add(rec)
            }
            val filtered = candidates.filter { isSeriesTvEntry(it) }
            return filtered.ifEmpty { listOf(record) }
        }
        val kitsuIds = cachedAsset.indexes.byTvdb[tvdb]
        val candidates = if (!kitsuIds.isNullOrEmpty()) {
            kitsuIds.mapNotNull { cachedAsset.identityRecordsByKitsu[it] }
        } else {
            cachedAsset.identityRecordsByKitsu.values.filter { it.tvdb == tvdb }
        }
        val filtered = candidates.filter { isSeriesTvEntry(it) }
        return filtered.ifEmpty { listOf(record) }
    }

    // ---------------------------------------------------------------------
    // Reader-backed branches
    // ---------------------------------------------------------------------

    private fun resolveKitsuIdViaReader(
        reader: AnimeIdMapBinaryReader,
        id: AnimeStremioId,
        mediaKind: ContentMediaKind,
    ): String? {
        reader.ensureOpen()
        return when (id.source) {
            AnimeIdSource.KITSU -> {
                // Original: `id.value.takeIf { indexes.byKitsu.containsKey(it) } ?: id.value`
                // — i.e. always returns id.value regardless of presence. Preserve that.
                id.value
            }
            AnimeIdSource.MAL -> reader.lookupSingle(IndexKind.BY_MAL, id.value)
            AnimeIdSource.ANILIST -> reader.lookupSingle(IndexKind.BY_ANILIST, id.value)
            AnimeIdSource.ANIDB -> reader.lookupSingle(IndexKind.BY_ANIDB, id.value)
            AnimeIdSource.TVDB -> reader.lookupMultiFirst(IndexKind.BY_TVDB, id.value)
            AnimeIdSource.IMDB -> resolveImdbKitsuIdViaReader(reader, id.value, mediaKind)
            AnimeIdSource.TMDB -> when (mediaKind) {
                ContentMediaKind.MOVIE -> reader.lookupSingle(IndexKind.BY_TMDB_MOVIE, id.value)
                ContentMediaKind.SERIES -> reader.lookupMultiFirst(IndexKind.BY_TMDB_TV, id.value)
            }
        }
    }

    private fun resolveImdbKitsuIdViaReader(
        reader: AnimeIdMapBinaryReader,
        imdbId: String,
        mediaKind: ContentMediaKind,
    ): String? {
        val offsets = reader.recordOffsetsForImdb(imdbId)
        if (offsets.isEmpty()) return null
        // Match the asset path: prefer first record whose mediaType matches the
        // requested kind, fall back to the first candidate's kitsu id.
        var firstKitsu: String? = null
        for (i in offsets.indices) {
            val rec = reader.recordAt(offsets[i]) ?: continue
            if (firstKitsu == null) firstKitsu = rec.kitsu
            if (rec.matches(mediaKind)) return rec.kitsu
        }
        return firstKitsu
    }

    // ---------------------------------------------------------------------
    // Asset-backed branches (legacy in-memory)
    // ---------------------------------------------------------------------

    private fun resolveKitsuIdViaAsset(
        asset: AnimeIdMapAsset,
        id: AnimeStremioId,
        mediaKind: ContentMediaKind,
    ): String? {
        val indexes = asset.indexes
        return when (id.source) {
            AnimeIdSource.KITSU -> id.value.takeIf { indexes.byKitsu.containsKey(it) } ?: id.value
            AnimeIdSource.MAL -> indexes.byMal[id.value]
            AnimeIdSource.ANILIST -> indexes.byAnilist[id.value]
            AnimeIdSource.ANIDB -> indexes.byAnidb[id.value]
            AnimeIdSource.TVDB -> indexes.byTvdb[id.value]?.firstOrNull()
            AnimeIdSource.IMDB -> resolveImdbKitsuIdViaAsset(asset, id.value, mediaKind)
            AnimeIdSource.TMDB -> when (mediaKind) {
                ContentMediaKind.MOVIE -> indexes.byTmdbMovie[id.value]
                ContentMediaKind.SERIES -> indexes.byTmdbTv[id.value]?.firstOrNull()
            }
        }
    }

    private fun resolveImdbKitsuIdViaAsset(
        asset: AnimeIdMapAsset,
        imdbId: String,
        mediaKind: ContentMediaKind,
    ): String? {
        val candidates = asset.indexes.byImdb[imdbId] ?: return null
        if (candidates.isEmpty()) return null
        val records = candidates.mapNotNull { asset.identityRecordsByKitsu[it] }
        val matched = records.firstOrNull { rec -> rec.matches(mediaKind) }
        return matched?.kitsu ?: candidates.firstOrNull()
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

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
