package com.nexio.tv.core.anime

import android.util.Log
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeCharacter
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeDetail
import com.nexio.tv.core.tvdb.KitsuAdvancedAnimeStaffMember
import com.nexio.tv.core.tvdb.KitsuAdvancedProductionCompany
import com.nexio.tv.core.tvdb.KitsuAdvancedRelatedTitle
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.data.remote.api.KitsuApi
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
import com.nexio.tv.data.repository.KitsuAuthService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "KitsuMetadata"
private const val KITSU_EPISODE_PAGE_LIMIT = 20
private const val KITSU_EPISODE_MAX_PAGES = 100

@Singleton
class KitsuMetadataService @Inject constructor(
    private val api: KitsuApi,
    private val idMappingService: AnimeIdMappingService,
    private val kitsuAuthService: KitsuAuthService
) {
    suspend fun fetchEnrichment(rawId: String, mediaKind: ContentMediaKind): TvMetadataEnrichment? =
        withContext(Dispatchers.IO) {
            val animeId = AnimeStremioId.parse(rawId) ?: return@withContext null
            val kitsuId = when (animeId.source) {
                AnimeIdSource.KITSU -> animeId.value
                else -> {
                    if (!kitsuAuthService.providerAuthenticated()) return@withContext null
                    idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext null
                }
            }
            val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
            val response = runCatching {
                api.getAnime(authorization, kitsuId)
            }.onFailure {
                Log.w(TAG, "Kitsu anime fetch failed id=$rawId reason=${it.javaClass.simpleName}")
            }.getOrNull() ?: return@withContext null

            val resource = response.takeIf { it.isSuccessful }?.body()?.data ?: return@withContext null
            val attributes = resource.attributes ?: return@withContext null
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

    suspend fun fetchEpisodeEnrichment(
        rawId: String,
        mediaKind: ContentMediaKind,
        seasonNumbers: List<Int>
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> = withContext(Dispatchers.IO) {
        val animeId = AnimeStremioId.parse(rawId) ?: return@withContext emptyMap()
        val kitsuId = when (animeId.source) {
            AnimeIdSource.KITSU -> animeId.value
            else -> {
                if (!kitsuAuthService.providerAuthenticated()) return@withContext emptyMap()
                idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext emptyMap()
            }
        }
        val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
        val episodes = fetchEpisodePages(authorization, kitsuId, rawId)

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
        val kitsuId = idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext null
        val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }

        val castingsResponse = runCatching {
            api.getCastingsByMedia(authorization = authorization, mediaId = kitsuId)
        }.onFailure {
            Log.w(TAG, "Kitsu castings fetch failed id=$rawId reason=${it.javaClass.simpleName}")
        }.getOrNull()

        val animeStaffResponse = runCatching {
            api.getAnimeStaff(authorization = authorization, id = kitsuId)
        }.onFailure {
            Log.w(TAG, "Kitsu anime staff fetch failed id=$rawId reason=${it.javaClass.simpleName}")
        }.getOrNull()

        val animeProductionsResponse = runCatching {
            api.getAnimeProductions(authorization = authorization, id = kitsuId)
        }.onFailure {
            Log.w(TAG, "Kitsu anime productions fetch failed id=$rawId reason=${it.javaClass.simpleName}")
        }.getOrNull()

        val mediaRelationshipsResponse = runCatching {
            api.getAnimeMediaRelationships(authorization = authorization, id = kitsuId)
        }.onFailure {
            Log.w(TAG, "Kitsu media relationships fetch failed id=$rawId reason=${it.javaClass.simpleName}")
        }.getOrNull()

        KitsuAdvancedAnimeDetail(
            characters = castingsResponse.toAdvancedCharacters(
                preferredLanguageCode = preferredLanguageCode
            ),
            staff = animeStaffResponse.toAdvancedStaff(),
            // `anime/{id}/installments` currently returns 500 for real titles in live validation,
            // so keep Related scoped to the stable relationship graph until Kitsu's installment
            // surface proves reliable enough to consume in-product.
            relatedTitles = mediaRelationshipsResponse
                .toAdvancedRelatedTitles()
                .filter { it.mediaType.equals("anime", ignoreCase = true) }
                .distinctBy { "${it.mediaType}:${it.mediaId}" },
            productionCompanies = animeProductionsResponse.toAdvancedProductionCompanies(),
            franchiseIds = emptySet()
        )
    }

    private suspend fun fetchEpisodePages(
        authorization: String?,
        kitsuId: String,
        rawId: String
    ): List<KitsuAnimeResource> {
        val episodes = mutableListOf<KitsuAnimeResource>()
        var offset = 0
        var pageCount = 0
        while (pageCount < KITSU_EPISODE_MAX_PAGES) {
            val response = runCatching {
                api.getAnimeEpisodes(
                    authorization = authorization,
                    id = kitsuId,
                    limit = KITSU_EPISODE_PAGE_LIMIT,
                    offset = offset
                )
            }.onFailure {
                Log.w(TAG, "Kitsu episode fetch failed id=$rawId offset=$offset reason=${it.javaClass.simpleName}")
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

private fun KitsuImage.bestUrl(): String? =
    original ?: large ?: medium ?: small ?: tiny

private fun retrofit2.Response<*>?.isSuccessfulWithBody(): Boolean = this?.isSuccessful == true && this.body() != null

private fun retrofit2.Response<KitsuCollectionResponse<KitsuCastingResource>>?.toAdvancedCharacters(
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
