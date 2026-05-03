package com.nexio.tv.core.anime

import com.nexio.tv.data.integration.kitsu.KitsuAdvancedDetailPayload
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeCharacter
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeDetail
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeStaffMember
import com.nexio.tv.core.tvdb.KitsuAdvancedProductionCompany
import com.nexio.tv.core.tvdb.KitsuAdvancedRelatedTitle
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.core.metadata.router.ReviewsPage
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterResource
import com.nexio.tv.data.remote.api.KitsuAnimeProductionResource
import com.nexio.tv.data.remote.api.KitsuAnimeStaffResource
import com.nexio.tv.data.remote.api.KitsuCastingResource
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.data.remote.api.KitsuIncludedResource
import com.nexio.tv.data.remote.api.KitsuInstallmentResource
import com.nexio.tv.data.remote.api.KitsuMediaRelationshipResource
import com.nexio.tv.data.remote.api.KitsuReviewResource
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class KitsuMetadataService @Inject constructor(
    private val provider: KitsuIntegrationProvider,
    private val idMappingService: AnimeIdMappingService
) {

    suspend fun fetchEnrichment(rawId: String, mediaKind: ContentMediaKind): TvMetadataEnrichment? =
        withContext(Dispatchers.IO) {
            val animeId = AnimeStremioId.parse(rawId) ?: return@withContext null
            val kitsuId = when (animeId.source) {
                AnimeIdSource.KITSU -> animeId.value
                else -> idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext null
            }
            provider.fetchEnrichment(rawId = rawId, kitsuId = kitsuId, mediaKind = mediaKind) { resource ->
                val attributes = resource.attributes ?: return@fetchEnrichment null
                TvMetadataEnrichment(
                    seriesTvdbId = null,
                    localizedTitle = attributes.canonicalTitle,
                    description = attributes.synopsis ?: attributes.description,
                    genres = emptyList(),
                    backdrop = attributes.coverImage?.bestUrl(),
                    poster = attributes.posterImage?.bestUrl(),
                    releaseInfo = attributes.startDate,
                    rating = null,
                    runtimeMinutes = attributes.episodeLength,
                    ageRating = attributes.ageRating,
                    language = "ja",
                    status = attributes.status,
                    remoteIds = mapOf("kitsu" to setOf(kitsuId))
                )
            }
        }

    suspend fun fetchEpisodeEnrichment(
        rawId: String,
        mediaKind: ContentMediaKind,
        seasonNumbers: List<Int>
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> = withContext(Dispatchers.IO) {
        val animeId = AnimeStremioId.parse(rawId) ?: return@withContext emptyMap()
        val kitsuId = when (animeId.source) {
            AnimeIdSource.KITSU -> animeId.value
            else -> idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext emptyMap()
        }
        provider.fetchEpisodeEnrichment(rawId = rawId, kitsuId = kitsuId, mediaKind = mediaKind) { episodes ->
            val acceptedSeasons = seasonNumbers.toSet()
            episodes
                .mapNotNull { episode ->
                    val attributes = episode.attributes ?: return@mapNotNull null
                    val season = attributes.seasonNumber ?: 1
                    val number = attributes.number ?: return@mapNotNull null
                    if (acceptedSeasons.isNotEmpty() && season !in acceptedSeasons) return@mapNotNull null
                    (season to number) to TvEpisodeMetadata(
                        providerEpisodeId = episode.id?.let { "kitsu:$it" },
                        seasonNumber = season,
                        episodeNumber = number,
                        title = attributes.canonicalTitle,
                        overview = attributes.synopsis ?: attributes.description,
                        thumbnail = attributes.thumbnail?.bestUrl(),
                        airDate = attributes.airdate,
                        runtimeMinutes = attributes.length
                    )
                }
                .toMap()
        }
    }

    suspend fun fetchSeasonEpisodes(
        rawId: String,
        mediaKind: ContentMediaKind,
        seasonNumber: Int
    ): List<TvSeasonEpisode> {
        val episodes = fetchEpisodeEnrichment(rawId, mediaKind, listOf(seasonNumber))
        return episodes.values
            .filter { episode -> episode.episodeNumber != null }
            .sortedBy { episode -> episode.episodeNumber }
            .map { episode ->
                TvSeasonEpisode(
                    episodeNumber = episode.episodeNumber,
                    airDate = episode.airDate,
                    metadata = episode
                )
            }
    }

    suspend fun fetchAdvancedDetail(
        rawId: String,
        mediaKind: ContentMediaKind,
        preferredLanguageCode: String? = null
    ): KitsuAdvancedAnimeDetail? = withContext(Dispatchers.IO) {
        val animeId = AnimeStremioId.parse(rawId) ?: return@withContext null
        val kitsuId = when (animeId.source) {
            AnimeIdSource.KITSU -> animeId.value
            else -> idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext null
        }
        provider.fetchAdvancedDetail(rawId = rawId, kitsuId = kitsuId, mediaKind = mediaKind) { payload ->
            payload.toAdvancedDetail(preferredLanguageCode)
        }
    }

    suspend fun fetchReviews(
        rawId: String,
        mediaKind: ContentMediaKind,
        page: Int = 1,
        limit: Int = 20
    ): ReviewsPage = withContext(Dispatchers.IO) {
        val animeId = AnimeStremioId.parse(rawId) ?: return@withContext ReviewsPage(emptyList(), false, null)
        val kitsuId = when (animeId.source) {
            AnimeIdSource.KITSU -> animeId.value
            else -> idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext ReviewsPage(emptyList(), false, null)
        }
        provider.fetchReviews(rawId = rawId, kitsuId = kitsuId, mediaKind = mediaKind, page = page, limit = limit)
            .toKitsuReviewsPage(page = page, limit = limit.coerceIn(1, 20))
    }
}

private fun KitsuImage.bestUrl(): String? =
    original ?: large ?: medium ?: small ?: tiny

private fun retrofit2.Response<*>?.isSuccessfulWithBody(): Boolean = this?.isSuccessful == true && this.body() != null

private fun retrofit2.Response<KitsuCollectionResponse<KitsuAnimeCharacterResource>>?.toAdvancedCharacters(): List<KitsuAdvancedAnimeCharacter> {
    if (!isSuccessfulWithBody()) return emptyList()
    val body = this?.body() ?: return emptyList()
    val includedByKey = body.included.orEmpty().associateBy { "${it.type}:${it.id}" }
    return body.data.orEmpty()
        .mapNotNull { relation ->
            val characterRef = relation.relationships?.character?.data ?: return@mapNotNull null
            val characterIncluded = includedByKey["${characterRef.type}:${characterRef.id}"] ?: return@mapNotNull null
            val characterName = characterIncluded.attributes.bestDisplayName() ?: return@mapNotNull null
            KitsuAdvancedAnimeCharacter(
                characterId = characterRef.id.orEmpty(),
                characterName = characterName,
                role = relation.attributes?.role,
                characterImage = characterIncluded.attributes.bestImageUrl(),
                actorId = null,
                actorName = null,
                actorImage = null,
                language = null,
                featured = relation.attributes?.role.equals("main", ignoreCase = true)
            )
        }
        .distinctBy { it.characterId }
        .sortedWith(compareByDescending<KitsuAdvancedAnimeCharacter> { it.featured }.thenBy { it.characterName.lowercase() })
}

private fun retrofit2.Response<KitsuCollectionResponse<KitsuCastingResource>>?.toAdvancedVoiceCharacters(
    preferredLanguageCode: String?
): List<KitsuAdvancedAnimeCharacter> {
    if (!isSuccessfulWithBody()) return emptyList()
    val body = this?.body() ?: return emptyList()
    val includedByKey = body.included.orEmpty().associateBy { "${it.type}:${it.id}" }
    return body.data.orEmpty()
        .filter { it.attributes?.voiceActor == true }
        .mapNotNull { relation ->
            val characterRef = relation.relationships?.character?.data ?: return@mapNotNull null
            val characterIncluded = includedByKey["${characterRef.type}:${characterRef.id}"] ?: return@mapNotNull null
            val characterName = characterIncluded.attributes.bestDisplayName() ?: return@mapNotNull null
            val personRef = relation.relationships?.person?.data
            val personIncluded = personRef?.let { includedByKey["${it.type}:${it.id}"] }
            KitsuAdvancedAnimeCharacter(
                characterId = characterRef.id.orEmpty(),
                characterName = characterName,
                role = relation.attributes?.role,
                characterImage = characterIncluded.attributes.bestImageUrl(),
                actorId = personRef?.id,
                actorName = personIncluded?.attributes.bestDisplayName(),
                actorImage = personIncluded?.attributes.bestImageUrl(),
                language = relation.attributes?.language,
                featured = relation.attributes?.featured == true
            )
        }
        .groupBy { it.characterId }
        .mapNotNull { (_, variants) ->
            variants.sortedWith(characterLanguageComparator(preferredLanguageCode)).firstOrNull()
        }
        .sortedWith(compareByDescending<KitsuAdvancedAnimeCharacter> { it.featured }.thenBy { it.characterName.lowercase() })
}

private fun retrofit2.Response<KitsuCollectionResponse<KitsuAnimeStaffResource>>?.toAdvancedStaff(): List<KitsuAdvancedAnimeStaffMember> {
    if (!isSuccessfulWithBody()) return emptyList()
    val body = this?.body() ?: return emptyList()
    val includedByKey = body.included.orEmpty().associateBy { "${it.type}:${it.id}" }
    return body.data.orEmpty().mapNotNull { relation ->
        val personRef = relation.relationships?.person?.data ?: return@mapNotNull null
        val included = includedByKey["${personRef.type}:${personRef.id}"] ?: return@mapNotNull null
        val name = included.attributes.bestDisplayName() ?: return@mapNotNull null
        KitsuAdvancedAnimeStaffMember(
            personId = personRef.id.orEmpty(),
            personName = name,
            role = relation.attributes?.role
        )
    }
}

private fun retrofit2.Response<KitsuCollectionResponse<KitsuAnimeProductionResource>>?.toAdvancedProductionCompanies(): List<KitsuAdvancedProductionCompany> {
    if (!isSuccessfulWithBody()) return emptyList()
    val body = this?.body() ?: return emptyList()
    val includedByKey = body.included.orEmpty().associateBy { "${it.type}:${it.id}" }
    return body.data.orEmpty().mapNotNull { relation ->
        val producerRef = relation.relationships?.producer?.data ?: return@mapNotNull null
        val included = includedByKey["${producerRef.type}:${producerRef.id}"] ?: return@mapNotNull null
        val name = included.attributes.bestDisplayName() ?: return@mapNotNull null
        KitsuAdvancedProductionCompany(
            producerId = producerRef.id.orEmpty(),
            producerName = name,
            role = relation.attributes?.role
        )
    }
}

private fun KitsuAdvancedDetailPayload.toAdvancedDetail(
    preferredLanguageCode: String?
): KitsuAdvancedAnimeDetail {
    return KitsuAdvancedAnimeDetail(
        characters = animeCharactersResponse.toAdvancedCharacters()
            .ifEmpty {
                castingsResponse.toAdvancedVoiceCharacters(
                    preferredLanguageCode = preferredLanguageCode
                )
            },
        staff = animeStaffResponse.toAdvancedStaff(),
        relatedTitles = mediaRelationshipsResponse
            .toAdvancedRelatedTitles()
            .filter { it.mediaType.equals("anime", ignoreCase = true) }
            .distinctBy { "${it.mediaType}:${it.mediaId}" },
        productionCompanies = animeProductionsResponse.toAdvancedProductionCompanies(),
        franchiseIds = emptySet()
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

private fun retrofit2.Response<KitsuCollectionResponse<KitsuMediaRelationshipResource>>?.toAdvancedRelatedTitles(): List<KitsuAdvancedRelatedTitle> {
    if (!isSuccessfulWithBody()) return emptyList()
    val body = this?.body() ?: return emptyList()
    val includedByKey = body.included.orEmpty().associateBy { "${it.type}:${it.id}" }
    return body.data.orEmpty().mapNotNull { relation ->
        val destinationRef = relation.relationships?.destination?.data ?: return@mapNotNull null
        val included = includedByKey["${destinationRef.type}:${destinationRef.id}"] ?: return@mapNotNull null
        val title = included.attributes.bestDisplayName() ?: return@mapNotNull null
        KitsuAdvancedRelatedTitle(
            mediaId = destinationRef.id.orEmpty(),
            mediaType = destinationRef.type.orEmpty(),
            title = title,
            synopsis = included.attributes.bestSynopsis(),
            poster = included.attributes.bestPosterUrl(),
            releaseInfo = included.attributes?.stringValue("startDate"),
            relationKind = relation.attributes?.role ?: "media_relationship"
        )
    }
}

private fun Map<String, Any?>?.bestDisplayName(): String? {
    if (this == null) return null
    return stringValue("canonicalTitle")
        ?: stringValue("canonicalName")
        ?: stringValue("name")
        ?: stringValue("title")
}

private fun Map<String, Any?>?.bestSynopsis(): String? {
    if (this == null) return null
    return stringValue("synopsis") ?: stringValue("description")
}

private fun Map<String, Any?>?.bestPosterUrl(): String? {
    if (this == null) return null
    val posterMap = mapValue("posterImage") ?: mapValue("image")
    return posterMap?.stringValue("original")
        ?: posterMap?.stringValue("large")
        ?: posterMap?.stringValue("medium")
        ?: posterMap?.stringValue("small")
        ?: posterMap?.stringValue("tiny")
}

private fun Map<String, Any?>?.bestImageUrl(): String? {
    if (this == null) return null
    val imageMap = mapValue("image") ?: mapValue("posterImage")
    return imageMap?.stringValue("original")
        ?: imageMap?.stringValue("large")
        ?: imageMap?.stringValue("medium")
        ?: imageMap?.stringValue("small")
        ?: imageMap?.stringValue("tiny")
}

private fun Map<String, Any?>.stringValue(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?>? =
    this[key] as? Map<String, Any?>

private fun characterLanguageComparator(preferredLanguageCode: String?): Comparator<KitsuAdvancedAnimeCharacter> {
    val ranked = when (preferredLanguageCode?.lowercase()) {
        "ja" -> listOf("Japanese", "English")
        "ko" -> listOf("Korean", "Japanese", "English")
        "zh", "zh-cn", "zh-tw", "cmn" -> listOf("Chinese", "Mandarin", "Japanese", "English")
        else -> listOf("Japanese", "English")
    }
    return compareBy<KitsuAdvancedAnimeCharacter> { character ->
        ranked.indexOfFirst { it.equals(character.language, ignoreCase = true) }
            .takeIf { it >= 0 } ?: Int.MAX_VALUE
    }.thenByDescending { it.featured }
}
