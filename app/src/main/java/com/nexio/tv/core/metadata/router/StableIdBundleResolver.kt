package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject

data class StableIdBundleRequest(
    val itemKey: String,
    val itemType: ContentType,
    val routeProvider: MetadataPrimaryProvider,
    val knownIds: ProviderIds,
    val sourceProvider: ProviderId?,
    val sourceItemId: String?,
    val railId: String?,
    val trigger: StableIdResolutionTrigger
)

class StableIdBundleResolver @Inject constructor(
    private val idMappingStore: IdMappingStore,
    private val lookup: Lookup,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    interface Lookup {
        suspend fun tmdbMovieToImdb(tmdbId: String): String?
        suspend fun imdbToTmdbMovie(imdbId: String): String?
        suspend fun tmdbTvToTvdb(tmdbId: String): String?
        suspend fun tmdbTvToImdb(tmdbId: String): String?
        suspend fun imdbToTvdbSeries(imdbId: String): String?
        suspend fun tvdbSeriesToImdb(tvdbId: String): String?
    }

    suspend fun resolve(request: StableIdBundleRequest): StableIdBundle {
        val evidence = mutableListOf<StableIdEvidence>()
        val known = request.knownIds

        var tmdbMovieId: String? = null
        var tvdbSeriesId: String? = null
        var kitsuAnimeId: String? = null
        var imdbId: String? = known.imdb.presentImdbId()

        when (request.routeProvider) {
            MetadataPrimaryProvider.TMDB -> {
                tmdbMovieId = known.tmdb.presentStableId()
                    ?: imdbId?.let { imdb ->
                        resolveViaStoreOrProvider(
                            sourceId = parsed(AnimeIdScheme.IMDB, imdb),
                            provider = MetadataPrimaryProvider.TMDB,
                            operation = "imdbToTmdbMovie",
                            target = "TMDB",
                            evidence = evidence
                        ) { lookup.imdbToTmdbMovie(imdb) }
                    }
                if (imdbId == null && tmdbMovieId != null) {
                    imdbId = resolveViaStoreOrProvider(
                        sourceId = tmdbMovieSourceId(tmdbMovieId),
                        provider = MetadataPrimaryProvider.IMDB,
                        operation = "tmdbMovieToImdb",
                        target = "IMDB",
                        evidence = evidence
                    ) { lookup.tmdbMovieToImdb(tmdbMovieId) }
                }
            }

            MetadataPrimaryProvider.TVDB -> {
                tvdbSeriesId = known.tvdb.presentStableId()
                    ?: imdbId?.let { imdb ->
                        resolveViaStoreOrProvider(
                            sourceId = parsed(AnimeIdScheme.IMDB, imdb),
                            provider = MetadataPrimaryProvider.TVDB,
                            operation = "imdbToTvdbSeries",
                            target = "TVDB",
                            evidence = evidence
                        ) { lookup.imdbToTvdbSeries(imdb) }
                    }

                if (tvdbSeriesId == null) {
                    val tmdbTvId = known.tmdb.presentStableId()
                    if (tmdbTvId != null) {
                        tvdbSeriesId = resolveViaStoreOrProvider(
                            sourceId = tmdbTvSourceId(tmdbTvId),
                            provider = MetadataPrimaryProvider.TVDB,
                            operation = "tmdbTvToTvdb",
                            target = "TVDB",
                            evidence = evidence
                        ) { lookup.tmdbTvToTvdb(tmdbTvId) }
                        val bridgeImdb = if (imdbId == null || tvdbSeriesId == null) resolveViaStoreOrProvider(
                            sourceId = tmdbTvSourceId(tmdbTvId),
                            provider = MetadataPrimaryProvider.IMDB,
                            operation = "tmdbTvToImdb",
                            target = "IMDB",
                            evidence = evidence
                        ) { lookup.tmdbTvToImdb(tmdbTvId) } else null
                        imdbId = imdbId ?: bridgeImdb
                        tvdbSeriesId = tvdbSeriesId ?: bridgeImdb?.let { imdb ->
                            resolveViaStoreOrProvider(
                                sourceId = parsed(AnimeIdScheme.IMDB, imdb),
                                provider = MetadataPrimaryProvider.TVDB,
                                operation = "imdbToTvdbSeries",
                                target = "TVDB",
                                evidence = evidence
                            ) { lookup.imdbToTvdbSeries(imdb) }
                        }
                        if (tvdbSeriesId != null) {
                            imdbId = bridgeImdb
                        }
                    }
                }

                if (imdbId == null && tvdbSeriesId != null) {
                    imdbId = resolveViaStoreOrProvider(
                        sourceId = parsed(AnimeIdScheme.TVDB, tvdbSeriesId),
                        provider = MetadataPrimaryProvider.IMDB,
                        operation = "tvdbSeriesToImdb",
                        target = "IMDB",
                        evidence = evidence
                    ) { lookup.tvdbSeriesToImdb(tvdbSeriesId) }
                }
            }

            MetadataPrimaryProvider.KITSU -> {
                kitsuAnimeId = known.kitsu.presentStableId()
                    ?: resolveKitsuFromStore(
                        known = known,
                        evidence = evidence
                    )
            }

            MetadataPrimaryProvider.IMDB,
            MetadataPrimaryProvider.TRAKT,
            MetadataPrimaryProvider.SIMKL,
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> Unit
        }

        return StableIdBundle(
            itemKey = request.itemKey,
            itemType = request.itemType,
            canonical = CanonicalStableIds(
                tmdbMovieId = tmdbMovieId.presentStableId(),
                tvdbSeriesId = tvdbSeriesId.presentStableId(),
                kitsuAnimeId = kitsuAnimeId.presentStableId()
            ),
            sidecars = SidecarStableIds(
                imdbId = imdbId.presentStableId(),
                malId = known.mal.presentStableId(),
                anilistId = known.anilist.presentStableId(),
                anidbId = known.anidb.presentStableId()
            ),
            source = known.toSourceStableIds(
                sourceProvider = request.sourceProvider,
                sourceItemId = request.sourceItemId,
                railId = request.railId
            ),
            evidence = evidence,
            resolvedAtMs = nowEpochMs()
        )
    }

    private suspend fun resolveFromStore(
        sourceId: ParsedMetadataId,
        provider: MetadataPrimaryProvider,
        operation: String,
        target: String,
        evidence: MutableList<StableIdEvidence>
    ): String? {
        val raw = idMappingStore.readRaw(provider, sourceId) ?: return null
        if (raw.source == IdMappingSource.NEGATIVE) {
            evidence += StableIdEvidence("store.NEGATIVE.$operation", target, false)
            return null
        }
        return raw.providerId.presentStableId()?.also { id ->
            evidence += StableIdEvidence("store.${raw.source}.$operation", target, false, id)
        }
    }

    private suspend fun resolveViaStoreOrProvider(
        sourceId: ParsedMetadataId,
        provider: MetadataPrimaryProvider,
        operation: String,
        target: String,
        evidence: MutableList<StableIdEvidence>,
        providerLookup: suspend () -> String?
    ): String? {
        resolveFromStore(sourceId, provider, operation, target, evidence)?.let { return it }
        if (idMappingStore.readRaw(provider, sourceId)?.source == IdMappingSource.NEGATIVE) {
            return null
        }

        val resolved = providerLookup().presentStableId()
        evidence += StableIdEvidence("providerLookup.$operation", target, true, resolved)
        persistLookup(sourceId, provider, operation, resolved)
        return resolved
    }

    private suspend fun persistLookup(
        sourceId: ParsedMetadataId,
        provider: MetadataPrimaryProvider,
        operation: String,
        providerId: String?
    ) {
        val source = if (providerId == null) IdMappingSource.NEGATIVE else IdMappingSource.PROVIDER_LOOKUP
        idMappingStore.persist(
            IdMapping(
                sourceId = sourceId,
                provider = provider,
                providerId = providerId ?: "",
                source = source,
                evidence = operation,
                expiresAtEpochMs = IdMappingTtlPolicy.expiresAt(source, nowEpochMs())
            )
        )
    }

    private fun parsed(scheme: AnimeIdScheme, id: String): ParsedMetadataId =
        MetadataIdParser.parse("${scheme.name.lowercase()}:$id")

    private fun tmdbMovieSourceId(id: String): ParsedMetadataId {
        val value = id.presentStableId() ?: id
        return ParsedMetadataId(AnimeIdScheme.TMDB, "movie:$value", "tmdb:movie:$value")
    }

    private fun tmdbTvSourceId(id: String): ParsedMetadataId {
        val value = id.presentStableId() ?: id
        return ParsedMetadataId(AnimeIdScheme.TMDB, "tv:$value", "tmdb:tv:$value")
    }

    private suspend fun resolveKitsuFromStore(
        known: ProviderIds,
        evidence: MutableList<StableIdEvidence>
    ): String? {
        val candidates = listOf(
            known.mal to AnimeIdScheme.MAL,
            known.anilist to AnimeIdScheme.ANILIST,
            known.anidb to AnimeIdScheme.ANIDB,
            known.imdb to AnimeIdScheme.IMDB
        )
        for ((id, scheme) in candidates) {
            val parsed = id.presentStableId()?.let { parsed(scheme, it) } ?: continue
            val kitsuId = resolveFromStore(
                sourceId = parsed,
                provider = MetadataPrimaryProvider.KITSU,
                operation = "sourceToKitsu",
                target = "KITSU",
                evidence = evidence
            )
            if (kitsuId != null) return kitsuId
        }
        return null
    }

    private fun String?.presentStableId(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String?.presentImdbId(): String? {
        val value = presentStableId() ?: return null
        val parsed = MetadataIdParser.parse(value)
        return when (parsed.scheme) {
            AnimeIdScheme.IMDB -> parsed.value.presentStableId()
            else -> value.lowercase()
        }
    }
}
