package com.nexio.tv.data.integration.kitsu

import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeDetail
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.integration.metadata.LocalizationPolicy
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterResource
import com.nexio.tv.data.remote.api.KitsuAnimeProductionResource
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuAnimeStaffResource
import com.nexio.tv.data.remote.api.KitsuCastingResource
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.remote.api.KitsuMediaRelationshipResource
import com.nexio.tv.data.repository.KitsuAuthService
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

@Singleton
class KitsuIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val kitsuApi: KitsuApi,
    private val kitsuAuthService: KitsuAuthService,
    private val ownershipFactory: IntegrationCacheOwnershipFactory = IntegrationCacheOwnershipFactory(
        RailMediaIdentityResolver()
    )
) {
    suspend fun fetchEnrichment(
        rawId: String,
        kitsuId: String,
        mediaKind: ContentMediaKind,
        mapper: (KitsuAnimeResource) -> TvMetadataEnrichment?
    ): TvMetadataEnrichment? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.ANIME_CORE,
            operationKey = "kitsu.fetch_enrichment",
            // F2-E-02: include policyVersion so a LocalizationPolicy.CURRENT_VERSION bump
            // automatically invalidates previously cached enrichment entries.
            cacheKey = "kitsu:${mediaKind.name.lowercase()}:$rawId:enrichment:policy:${LocalizationPolicy.CURRENT_VERSION}",
            codec = gsonCodec<TvMetadataEnrichment>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = if (mediaKind == ContentMediaKind.MOVIE) "movie" else "series",
                rawId = rawId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                val response = runCatching { kitsuApi.getAnime(authorization, kitsuId) }
                    .getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                val body = response.body()?.data ?: return@IntegrationSpec IntegrationLoadResult.HttpError(response.code(), reason = "missing_body")
                val enrichment = mapper(body) ?: return@IntegrationSpec IntegrationLoadResult.HttpError(response.code(), reason = "mapping_failed")
                IntegrationLoadResult.Success(enrichment)
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchEpisodeEnrichment(
        rawId: String,
        kitsuId: String,
        mediaKind: ContentMediaKind,
        mapper: (List<KitsuAnimeResource>) -> Map<Pair<Int, Int>, TvEpisodeMetadata>
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.ANIME_EPISODES,
            operationKey = "kitsu.fetch_episode_enrichment",
            cacheKey = "kitsu:${mediaKind.name.lowercase()}:$rawId:episodes",
            codec = gsonCodec<Map<Pair<Int, Int>, TvEpisodeMetadata>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = if (mediaKind == ContentMediaKind.MOVIE) "movie" else "series",
                rawId = rawId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                IntegrationLoadResult.Success(
                    mapper(fetchEpisodePages(authorization = authorization, kitsuId = kitsuId))
                )
            }
        )
        return runtime.get(spec).valueOrNull().orEmpty()
    }

    suspend fun fetchAdvancedDetail(
        rawId: String,
        kitsuId: String,
        mediaKind: ContentMediaKind,
        mapper: (KitsuAdvancedDetailPayload) -> KitsuAdvancedAnimeDetail
    ): KitsuAdvancedAnimeDetail? {
        val payload = KitsuAdvancedDetailPayload(
            castingsResponse = fetchCastings(rawId, kitsuId, mediaKind)?.let { Response.success(it) },
            animeStaffResponse = fetchAnimeStaff(rawId, kitsuId, mediaKind)?.let { Response.success(it) },
            animeProductionsResponse = fetchAnimeProductions(rawId, kitsuId, mediaKind)?.let { Response.success(it) },
            mediaRelationshipsResponse = fetchMediaRelationships(rawId, kitsuId, mediaKind)?.let { Response.success(it) }
        )
        return mapper(payload)
    }

    suspend fun fetchCastings(
        rawId: String,
        kitsuId: String,
        mediaKind: ContentMediaKind
    ): KitsuCollectionResponse<KitsuCastingResource>? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.CASTINGS,
            operationKey = "kitsu.castings",
            cacheKey = "kitsu:${mediaKind.name.lowercase()}:$rawId:castings",
            codec = gsonCodec<KitsuCollectionResponse<KitsuCastingResource>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = if (mediaKind == ContentMediaKind.MOVIE) "movie" else "series",
                rawId = rawId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                val response = runCatching {
                    kitsuApi.getCastingsByMedia(authorization = authorization, mediaId = kitsuId)
                }.getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "kitsu_castings_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchAnimeStaff(
        rawId: String,
        kitsuId: String,
        mediaKind: ContentMediaKind
    ): KitsuCollectionResponse<KitsuAnimeStaffResource>? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.ANIME_STAFF,
            operationKey = "kitsu.anime_staff",
            cacheKey = "kitsu:${mediaKind.name.lowercase()}:$rawId:anime_staff",
            codec = gsonCodec<KitsuCollectionResponse<KitsuAnimeStaffResource>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = if (mediaKind == ContentMediaKind.MOVIE) "movie" else "series",
                rawId = rawId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                val response = runCatching {
                    kitsuApi.getAnimeStaff(authorization = authorization, id = kitsuId)
                }.getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "kitsu_anime_staff_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchAnimeProductions(
        rawId: String,
        kitsuId: String,
        mediaKind: ContentMediaKind
    ): KitsuCollectionResponse<KitsuAnimeProductionResource>? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.ANIME_PRODUCTIONS,
            operationKey = "kitsu.anime_productions",
            cacheKey = "kitsu:${mediaKind.name.lowercase()}:$rawId:anime_productions",
            codec = gsonCodec<KitsuCollectionResponse<KitsuAnimeProductionResource>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = if (mediaKind == ContentMediaKind.MOVIE) "movie" else "series",
                rawId = rawId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                val response = runCatching {
                    kitsuApi.getAnimeProductions(authorization = authorization, id = kitsuId)
                }.getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "kitsu_anime_productions_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchMediaRelationships(
        rawId: String,
        kitsuId: String,
        mediaKind: ContentMediaKind
    ): KitsuCollectionResponse<KitsuMediaRelationshipResource>? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.MEDIA_RELATIONSHIPS,
            operationKey = "kitsu.media_relationships",
            cacheKey = "kitsu:${mediaKind.name.lowercase()}:$rawId:media_relationships",
            codec = gsonCodec<KitsuCollectionResponse<KitsuMediaRelationshipResource>>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = if (mediaKind == ContentMediaKind.MOVIE) "movie" else "series",
                rawId = rawId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                val response = runCatching {
                    kitsuApi.getAnimeMediaRelationships(authorization = authorization, id = kitsuId)
                }.getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "kitsu_media_relationships_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun searchAnimeByText(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): KitsuCollectionResponse<KitsuAnimeResource>? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.SEARCH_TEXT,
            operationKey = "kitsu.search.text",
            cacheKey = null,
            codec = gsonCodec<KitsuCollectionResponse<KitsuAnimeResource>>(),
            cachePolicy = IntegrationCachePolicy.ObserveOnly("kitsu_text_search_request_scoped"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
                val response = runCatching {
                    kitsuApi.getAnimeCollection(
                        authorization = authorization,
                        text = query,
                        limit = limit,
                        offset = offset
                    )
                }.getOrElse { return@IntegrationSpec IntegrationLoadResult.NetworkError(it) }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "kitsu_search_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    private suspend fun fetchEpisodePages(
        authorization: String?,
        kitsuId: String
    ): List<KitsuAnimeResource> {
        val episodes = mutableListOf<KitsuAnimeResource>()
        var offset = 0
        var pageCount = 0
        while (pageCount < KITSU_EPISODE_MAX_PAGES) {
            val response = runCatching {
                kitsuApi.getAnimeEpisodes(
                    authorization = authorization,
                    id = kitsuId,
                    limit = KITSU_EPISODE_PAGE_LIMIT,
                    offset = offset
                )
            }.getOrNull() ?: break

            if (!response.isSuccessful) break
            val body = response.body() ?: break
            val page = body.data.orEmpty()
            episodes += page
            if (body.links?.next.isNullOrBlank() || page.isEmpty()) break

            offset += KITSU_EPISODE_PAGE_LIMIT
            pageCount += 1
        }
        return episodes
    }
}

data class KitsuAdvancedDetailPayload(
    val castingsResponse: Response<KitsuCollectionResponse<KitsuCastingResource>>?,
    val animeStaffResponse: Response<KitsuCollectionResponse<KitsuAnimeStaffResource>>?,
    val animeProductionsResponse: Response<KitsuCollectionResponse<KitsuAnimeProductionResource>>?,
    val mediaRelationshipsResponse: Response<KitsuCollectionResponse<KitsuMediaRelationshipResource>>?
)

private const val KITSU_EPISODE_PAGE_LIMIT = 20
private const val KITSU_EPISODE_MAX_PAGES = 100
