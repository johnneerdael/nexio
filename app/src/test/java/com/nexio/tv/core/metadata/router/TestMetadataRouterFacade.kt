package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.tvdb.ProviderMetadataRouter
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.ContentType

fun testMetadataRouterFacade(
    providerMetadataRouter: ProviderMetadataRouter,
    metadataSecondaryRepository: MetadataSecondaryRepository? = null,
    trailerService: TrailerService? = null
): MetadataRouterFacade =
    MetadataRouterFacade(
        router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore()
        ),
        providerPlanExecutor = ProviderPlanExecutor(),
        resolverOrchestrator = ResolverOrchestrator(),
        identityResolver = MetadataIdentityResolver(object : MetadataIdentityResolver.Lookup {
            override suspend fun tmdbToTvdb(tmdbId: String): String? = null
            override suspend fun tvdbToTmdb(tvdbId: String): String? = null
        }),
        providerPlanRunner = ProviderPlanRunner(
            MetadataPrimaryProvider.entries
                .map { provider -> TestMetadataProviderAdapter(provider, providerMetadataRouter) }
                .toSet()
        ),
        fieldResolver = FieldResolver(),
        metadataSecondaryRepository = metadataSecondaryRepository,
        trailerService = trailerService
    )

private class TestMetadataProviderAdapter(
    override val provider: MetadataPrimaryProvider,
    private val providerMetadataRouter: ProviderMetadataRouter
) : MetadataProviderAdapter {
    override fun supports(step: ProviderPlanStep): Boolean = true

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val coreEnrichment = when {
            route.seasonNumber == null && step.role == ProviderPlanRole.PRIMARY_CORE ->
                providerMetadataRouter.fetchEnrichment(
                    TvMetadataRequest(
                        contentId = route.parentId,
                        fallbackContentId = route.sourceContext.previewSourceItemId,
                        contentType = route.mediaKind.toContentType(),
                        language = route.language
                    )
                ).value
            else -> null
        }
        val episodeMetadata = when {
            route.seasonNumber == null -> emptyMap()
            step.role == ProviderPlanRole.SEASON -> {
                val seasonNumber = route.seasonNumber
                providerMetadataRouter.fetchSeasonEpisodes(
                    contentId = route.parentId,
                    fallbackContentId = route.parentId,
                    seasonNumber = seasonNumber,
                    language = route.language
                ).value.orEmpty().mapNotNull { episode ->
                    val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                    (seasonNumber to episodeNumber) to episode.metadata
                }.toMap()
            }
            else -> {
                providerMetadataRouter.fetchEpisodeEnrichment(
                    TvMetadataRequest(
                        contentId = route.parentId,
                        fallbackContentId = route.parentId,
                        contentType = route.mediaKind.toContentType(),
                        language = route.language,
                        seasonNumbers = listOfNotNull(route.seasonNumber)
                    )
                ).value.orEmpty()
            }
        }

        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = route.provider,
                fields = coreEnrichment?.toResolvedFields() ?: mapOf(
                    ResolvedField.TITLE to FieldValue("Test title", FieldOwner.PRIMARY)
                )
            ),
            episodeMetadata = episodeMetadata
        )
    }

    private fun TvMetadataEnrichment.toResolvedFields(): Map<ResolvedField, FieldValue> =
        buildMap {
            localizedTitle?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            description?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            poster?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            backdrop?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            logo?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
            rating?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            runtimeMinutes?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            seriesTvdbId?.let { put(ResolvedField.CANONICAL_ID, FieldValue("tvdb:$it", FieldOwner.PRIMARY)) }
        }

    private fun MetadataMediaKind.toContentType(): ContentType =
        when (this) {
            MetadataMediaKind.MOVIE -> ContentType.MOVIE
            MetadataMediaKind.SERIES,
            MetadataMediaKind.ANIME,
            MetadataMediaKind.UNKNOWN -> ContentType.SERIES
        }
}
