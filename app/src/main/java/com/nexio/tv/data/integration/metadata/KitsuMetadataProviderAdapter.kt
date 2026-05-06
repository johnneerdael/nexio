package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ReviewsPage
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterResource
import com.nexio.tv.data.remote.api.KitsuAnimeProductionResource
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuAnimeStaffResource
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.remote.api.KitsuIncludedResource
import com.nexio.tv.data.remote.api.KitsuMediaRelationshipResource
import com.nexio.tv.data.remote.api.KitsuReviewResource
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import com.nexio.tv.domain.model.PosterShape
import javax.inject.Inject

class KitsuMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: KitsuIntegrationProvider,
    private val traceEvents: TraceMetadataEvents
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.KITSU

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in kitsuShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val kitsuId = MetadataProviderTargetIds.kitsu(route.targetIds[MetadataPrimaryProvider.KITSU])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val mediaKind = route.toAnimeContentMediaKind()
        val policy = LocalizationPolicy.kitsu(route.language)
        // F-E-02: emit localization_plan after policy construction. Kitsu has no per-episode
        // localization bundle, so perEpisodeFallbacksAttempted stays 0.
        traceEvents.emitLocalizationPlan(
            contentId = "kitsu:$kitsuId",
            provider = "KITSU",
            policyVersion = policy.policyVersion,
            requestedLanguage = policy.requestedLanguage.providerCode,
            fallbackLanguage = policy.fallbackLanguage.providerCode,
            requestedIsFallback = policy.requestedIsFallback,
            allowProviderFallbackForMissingLocalizedFields = policy.allowProviderFallbackForMissingLocalizedFields,
            perEpisodeFallbacksAttempted = 0,
            perEpisodeFallbacksAllowed = policy.maxPerEpisodeTranslationFallbacksPerRequest
        )
        var titleField: SelectedLocalizedField? = null
        var synopsisField: SelectedLocalizedField? = null
        val candidate = when (step.apiShapeId) {
            KitsuApiShapes.ANIME_CORE ->
                integrationProvider.fetchEnrichment(rawId = route.parentId, kitsuId = kitsuId, mediaKind = mediaKind) { resource ->
                    val attributes = resource.attributes ?: return@fetchEnrichment null
                    val titles = attributes.titles.orEmpty()
                    titleField = selectKitsuTitleField(
                        policy = policy,
                        titles = titles,
                        canonicalTitle = attributes.canonicalTitle,
                        romanizedTitle = titles["en_jp"]
                    )
                    synopsisField = selectKitsuSynopsisField(
                        policy = policy,
                        synopsis = attributes.synopsis,
                        description = attributes.description
                    )
                    TvMetadataEnrichment(
                        seriesTvdbId = null,
                        localizedTitle = titleField?.value,
                        description = synopsisField?.value,
                        backdrop = attributes.coverImage.bestUrl(),
                        poster = attributes.posterImage.bestUrl(),
                        releaseInfo = attributes.startDate,
                        runtimeMinutes = attributes.episodeLength,
                        ageRating = attributes.ageRating,
                        language = "ja",
                        status = attributes.status,
                        remoteIds = mapOf("kitsu" to setOf(kitsuId))
                    )
                }
                    .toMetadataCandidate(this.provider)
                    .withKitsuCanonicalId(kitsuId)
                    .withLocalizationTrace(titleField, synopsisField)
            KitsuApiShapes.ANIME_EPISODES -> {
                // F-E-03: Kitsu does not have per-episode localization decisions like TVDB. The Kitsu API
                // returns a single `attributes.titles` map at the series level (e.g. `en`, `en_jp`, `ja`)
                // and `attributes.synopsis` for the description; there is no per-(episode, field) winner
                // to emit. The series-level localization decision is captured by emitLocalizationPlan
                // (see Task 5 / commit 974a7fd4b).
                val episodeMetadata = integrationProvider.fetchEpisodeEnrichment(
                    rawId = route.parentId,
                    kitsuId = kitsuId,
                    mediaKind = mediaKind
                ) { episodes ->
                    episodes.toEpisodeMetadata(route.seasonNumber)
                }
                return ProviderStepResult(
                    step = step,
                    candidate = emptyCandidate(this.provider),
                    episodeMetadata = episodeMetadata
                )
            }
            KitsuApiShapes.CASTINGS -> {
                integrationProvider.fetchAnimeCharacters(route.parentId, kitsuId, mediaKind)
                    .toKitsuCharacterCandidate(this.provider)
            }
            KitsuApiShapes.ANIME_STAFF -> {
                integrationProvider.fetchAnimeStaff(route.parentId, kitsuId, mediaKind)
                    .toKitsuStaffCandidate(this.provider)
            }
            KitsuApiShapes.ANIME_PRODUCTIONS -> {
                integrationProvider.fetchAnimeProductions(route.parentId, kitsuId, mediaKind)
                    .toKitsuProductionCandidate(this.provider)
            }
            KitsuApiShapes.MEDIA_RELATIONSHIPS -> {
                integrationProvider.fetchMediaRelationships(route.parentId, kitsuId, mediaKind)
                    .toKitsuRelationshipCandidate(this.provider)
            }
            KitsuApiShapes.ANIME_REVIEWS -> {
                integrationProvider.fetchReviews(
                    rawId = route.parentId,
                    kitsuId = kitsuId,
                    mediaKind = mediaKind,
                    page = route.pagination?.page ?: 1,
                    limit = route.pagination?.limit ?: 20
                ).toKitsuReviewCandidate(this.provider, route.pagination?.page ?: 1, route.pagination?.limit ?: 20)
            }
            else -> emptyCandidate(this.provider)
        }
        return ProviderStepResult(step = step, candidate = candidate)
    }

    private fun MetadataRoute.toAnimeContentMediaKind(): ContentMediaKind {
        return when (sourceContext.itemType?.trim()?.lowercase()) {
            "movie", "film" -> ContentMediaKind.MOVIE
            "series", "tv", "show", "tvshow", "anime" -> ContentMediaKind.SERIES
            else -> if (mediaKind == MetadataMediaKind.MOVIE) ContentMediaKind.MOVIE else ContentMediaKind.SERIES
        }
    }

    private fun List<KitsuAnimeResource>.toEpisodeMetadata(
        seasonNumber: Int?
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> =
        mapNotNull { episode ->
            val attributes = episode.attributes ?: return@mapNotNull null
            val season = attributes.seasonNumber ?: 1
            val number = attributes.number ?: return@mapNotNull null
            if (seasonNumber != null && season != seasonNumber) {
                return@mapNotNull null
            }
            (season to number) to TvEpisodeMetadata(
                providerEpisodeId = episode.id?.let { "kitsu:$it" },
                seasonNumber = season,
                episodeNumber = number,
                title = attributes.canonicalTitle,
                overview = attributes.synopsis ?: attributes.description,
                thumbnail = attributes.thumbnail.bestUrl(),
                airDate = attributes.airdate,
                runtimeMinutes = attributes.length
            )
        }.toMap()

    private fun MetadataCandidate.withKitsuCanonicalId(kitsuId: String): MetadataCandidate =
        copy(fields = fields + (ResolvedField.CANONICAL_ID to FieldValue("kitsu:$kitsuId", FieldOwner.PRIMARY)))

    private fun MetadataCandidate.withLocalizationTrace(
        titleTrace: SelectedLocalizedField?,
        synopsisTrace: SelectedLocalizedField?
    ): MetadataCandidate =
        copy(
            localization = localization +
                listOfNotNull(
                    titleTrace?.let { ResolvedField.TITLE to it.toMetadataTrace() },
                    synopsisTrace?.let { ResolvedField.OVERVIEW to it.toMetadataTrace() }
                )
        )

    private fun KitsuCollectionResponse<KitsuAnimeCharacterResource>?.toKitsuCharacterCandidate(
        provider: MetadataPrimaryProvider
    ): MetadataCandidate {
        val includedByKey = includedByKey()
        val cast = this?.data.orEmpty()
            .mapNotNull { relation ->
                val relationships = relation.relationships ?: return@mapNotNull null
                val characterRef = relationships.character?.data ?: return@mapNotNull null
                val characterIncluded = includedByKey["${characterRef.type}:${characterRef.id}"] ?: return@mapNotNull null
                val characterName = characterIncluded.attributes.bestDisplayName() ?: return@mapNotNull null
                val voiceActor = relationships.castings?.data.orEmpty()
                    .asSequence()
                    .mapNotNull { castingRef -> includedByKey["${castingRef.type}:${castingRef.id}"] }
                    .mapNotNull { casting ->
                        val personRef = casting.relationships?.person?.data ?: return@mapNotNull null
                        val personIncluded = includedByKey["${personRef.type}:${personRef.id}"] ?: return@mapNotNull null
                        val personName = personIncluded.attributes.bestDisplayName() ?: return@mapNotNull null
                        personName
                    }
                    .firstOrNull()
                MetaCastMember(
                    name = characterName,
                    character = voiceActor ?: relation.attributes?.role,
                    photo = characterIncluded.attributes.bestImageUrl(),
                    provider = "kitsu",
                    providerId = characterRef.id
                )
            }
            .distinctBy { member -> "${member.name}|${member.character.orEmpty()}" }

        return MetadataCandidate(
            provider = provider,
            fields = buildMap {
                if (cast.isNotEmpty()) {
                    put(ResolvedField.CAST, FieldValue(cast, FieldOwner.PRIMARY))
                }
            }
        )
    }

    private fun KitsuCollectionResponse<KitsuAnimeStaffResource>?.toKitsuStaffCandidate(
        provider: MetadataPrimaryProvider
    ): MetadataCandidate {
        val includedByKey = includedByKey()
        val crew = this?.data.orEmpty()
            .mapNotNull { relation ->
                val personRef = relation.relationships?.person?.data ?: return@mapNotNull null
                val personIncluded = includedByKey["${personRef.type}:${personRef.id}"] ?: return@mapNotNull null
                val name = personIncluded.attributes.bestDisplayName() ?: return@mapNotNull null
                MetaCastMember(
                    name = name,
                    character = relation.attributes?.role,
                    photo = personIncluded.attributes.bestImageUrl(),
                    provider = "kitsu",
                    providerId = personRef.id
                )
            }
            .distinctBy { member -> "${member.name}|${member.character.orEmpty()}" }

        return MetadataCandidate(
            provider = provider,
            fields = buildMap {
                if (crew.isNotEmpty()) {
                    put(ResolvedField.CREW, FieldValue(crew, FieldOwner.PRIMARY))
                }
            }
        )
    }

    private fun KitsuCollectionResponse<KitsuAnimeProductionResource>?.toKitsuProductionCandidate(
        provider: MetadataPrimaryProvider
    ): MetadataCandidate {
        val includedByKey = includedByKey()
        val companies = this?.data.orEmpty()
            .mapNotNull { relation ->
                val producerRef = relation.relationships?.producer?.data ?: return@mapNotNull null
                val producerIncluded = includedByKey["${producerRef.type}:${producerRef.id}"] ?: return@mapNotNull null
                val name = producerIncluded.attributes.bestDisplayName() ?: return@mapNotNull null
                MetaCompany(
                    name = name,
                    kind = MetaCompanyKind.COMPANY,
                    provider = "kitsu",
                    providerId = producerRef.id
                )
            }
            .distinctBy { company -> company.providerId ?: company.name.lowercase() }

        return MetadataCandidate(
            provider = provider,
            fields = buildMap {
                if (companies.isNotEmpty()) {
                    put(ResolvedField.ORGANIZATION_LIST, FieldValue(companies, FieldOwner.PRIMARY))
                }
            }
        )
    }

    private fun KitsuCollectionResponse<KitsuMediaRelationshipResource>?.toKitsuRelationshipCandidate(
        provider: MetadataPrimaryProvider
    ): MetadataCandidate {
        val includedByKey = includedByKey()
        val related = this?.data.orEmpty()
            .mapNotNull { relation ->
                val destinationRef = relation.relationships?.destination?.data ?: return@mapNotNull null
                val destinationIncluded = includedByKey["${destinationRef.type}:${destinationRef.id}"] ?: return@mapNotNull null
                val attributes = destinationIncluded.attributes
                val title = attributes.bestDisplayName() ?: return@mapNotNull null
                MetaPreview(
                    id = "kitsu:${destinationRef.id}",
                    type = if (attributes.stringValue("subtype").equals("movie", ignoreCase = true)) {
                        ContentType.MOVIE
                    } else {
                        ContentType.SERIES
                    },
                    rawType = "anime",
                    name = title,
                    poster = attributes.bestPosterUrl(),
                    posterShape = PosterShape.POSTER,
                    background = attributes.bestCoverUrl(),
                    logo = null,
                    description = attributes.bestSynopsis(),
                    releaseInfo = attributes.stringValue("startDate")?.take(4),
                    imdbRating = null,
                    genres = emptyList()
                )
            }
            .distinctBy { preview -> preview.id }

        return MetadataCandidate(
            provider = provider,
            fields = buildMap {
                if (related.isNotEmpty()) {
                    put(ResolvedField.RECOMMENDATIONS, FieldValue(related, FieldOwner.RECOMMENDATIONS))
                }
            }
        )
    }

    private fun KitsuCollectionResponse<KitsuReviewResource>?.toKitsuReviewCandidate(
        provider: MetadataPrimaryProvider,
        page: Int,
        limit: Int
    ): MetadataCandidate {
        val reviewsPage = toKitsuReviewsPage(page = page.coerceAtLeast(1), limit = limit.coerceIn(1, 20))
        return MetadataCandidate(
            provider = provider,
            fields = buildMap {
                if (reviewsPage.reviews.isNotEmpty()) {
                    put(ResolvedField.REVIEWS, FieldValue(reviewsPage, FieldOwner.REVIEWS))
                }
            }
        )
    }

    private fun KitsuCollectionResponse<KitsuReviewResource>?.toKitsuReviewsPage(
        page: Int,
        limit: Int
    ): ReviewsPage {
        val body = this
        val includedByKey = body?.included.orEmpty().associateBy { "${it.type}:${it.id}" }
        val reviews = body?.data.orEmpty().mapNotNull { review ->
            val attributes = review.attributes ?: return@mapNotNull null
            val content = attributes.content?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val userRef = review.relationships?.user?.data
            val user = userRef?.let { includedByKey["${it.type}:${it.id}"] }
            MetaReview(
                id = review.id.orEmpty(),
                author = user?.attributes.bestDisplayName() ?: userRef?.id?.let { "Kitsu user $it" } ?: "Kitsu",
                content = content,
                rating = attributes.rating?.let { it / 2.0 },
                createdAt = attributes.createdAt,
                updatedAt = attributes.updatedAt,
                source = MetaReviewSource.KITSU,
                hasSpoiler = attributes.spoiler == true
            )
        }
        val rawCount = body?.data.orEmpty().size
        return ReviewsPage(
            reviews = reviews,
            hasMore = rawCount >= limit,
            nextPage = if (rawCount >= limit) page + 1 else null
        )
    }

    private fun KitsuCollectionResponse<*>?.includedByKey(): Map<String, KitsuIncludedResource> =
        this?.included.orEmpty().associateBy { "${it.type}:${it.id}" }

    private fun Map<String, Any?>?.bestDisplayName(): String? =
        stringValue("canonicalTitle")
            ?: stringValue("canonicalName")
            ?: stringValue("name")
            ?: stringValue("title")

    private fun Map<String, Any?>?.bestSynopsis(): String? =
        stringValue("synopsis") ?: stringValue("description")

    private fun Map<String, Any?>?.bestPosterUrl(): String? {
        val posterMap = mapValue("posterImage") ?: mapValue("image")
        return posterMap?.bestImageUrl()
    }

    private fun Map<String, Any?>?.bestCoverUrl(): String? {
        val coverMap = mapValue("coverImage")
        return coverMap?.bestImageUrl()
    }

    private fun Map<String, Any?>?.bestImageUrl(): String? {
        val imageMap = mapValue("image") ?: mapValue("posterImage")
        return imageMap?.bestImageUrl()
            ?: stringValue("original")
            ?: stringValue("large")
            ?: stringValue("medium")
            ?: stringValue("small")
            ?: stringValue("tiny")
    }

    private fun Map<String, Any?>?.stringValue(key: String): String? =
        this?.get(key)?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>?.mapValue(key: String): Map<String, Any?>? =
        (this?.get(key) as? Map<*, *>)?.entries
            ?.associate { entry -> entry.key.toString() to entry.value }

    private companion object {
        val kitsuShapes = setOf(
            KitsuApiShapes.ANIME_CORE,
            KitsuApiShapes.ANIME_EPISODES,
            KitsuApiShapes.CASTINGS,
            KitsuApiShapes.ANIME_STAFF,
            KitsuApiShapes.ANIME_PRODUCTIONS,
            KitsuApiShapes.MEDIA_RELATIONSHIPS,
            KitsuApiShapes.ANIME_REVIEWS
        )
    }
}
