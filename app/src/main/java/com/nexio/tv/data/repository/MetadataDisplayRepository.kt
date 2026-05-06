package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.ProviderPlanRunResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.PeopleDisplay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDetailDisplayDocument
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.orDefault
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataDisplayRepository @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade
) {
    suspend fun resolveDetailDisplay(request: MetadataRequest): ResolvedDetailDisplayDocument {
        val result = metadataRouterFacade.resolveRequest(request)
        val resolvedDocument = result.resolvedDocument
        val identity = resolvedDocument.toContentIdentity()

        return ResolvedDetailDisplayDocument(
            route = result.route,
            identity = identity,
            fields = resolvedDocument.toResolvedDisplayFields(),
            artwork = result.displayArtwork(),
            rating = result.toTitleRating(),
            trailer = result.toTrailerDisplayState(),
            seasons = emptyList(),
            people = resolvedDocument.castMembers
                .takeIf { it.isNotEmpty() }
                ?.let { PeopleDisplay(cast = it, crew = emptyList()) },
            reviews = result.providerRunResult.toFieldValues(ResolvedField.REVIEWS)
                .flatMap(::reviewsFrom),
            recommendations = result.providerRunResult.toFieldValues(ResolvedField.RECOMMENDATIONS)
                .flatMap(::recommendationsFrom),
            collection = emptyList(),
            sourceTrace = resolvedDocument.toSourceTrace(),
            localization = LocalizationDisplayState(
                requestedLanguage = request.language,
                selectedLanguage = resolvedDocument.language ?: request.language,
                fallbackReason = null
            )
        )
    }

    private fun ResolvedMetadataDocument.toContentIdentity(): ContentIdentity {
        val (provider, id) = canonicalId.parseCanonicalIdentity()

        return ContentIdentity(
            canonicalProvider = provider,
            canonicalId = id,
            providerIds = providerIdsFor(provider, id, remoteIds)
        )
    }

    private fun String?.parseCanonicalIdentity(): Pair<ProviderId?, String?> {
        val raw = this?.trim().orEmpty()
        val providerName = raw.substringBefore(':', missingDelimiterValue = "").trim()
        val id = raw.substringAfter(':', missingDelimiterValue = "").trim()
        if (providerName.isEmpty() || id.isEmpty()) return null to this

        val provider = ProviderId.entries.firstOrNull {
            it.name.equals(providerName, ignoreCase = true)
        }

        return provider to id
    }

    private fun providerIdsFor(
        provider: ProviderId?,
        id: String?,
        remoteIds: Map<String, Set<String>>
    ): ProviderIds {
        val remoteProviderIds = ProviderIds(
            imdb = remoteIds.firstValueFor("imdb"),
            tmdb = remoteIds.firstValueFor("tmdb"),
            tvdb = remoteIds.firstValueFor("tvdb"),
            trakt = remoteIds.firstValueFor("trakt"),
            simkl = remoteIds.firstValueFor("simkl"),
            kitsu = remoteIds.firstValueFor("kitsu"),
            mal = remoteIds.firstValueFor("mal"),
            anilist = remoteIds.firstValueFor("anilist"),
            anidb = remoteIds.firstValueFor("anidb")
        )
        if (id.isNullOrBlank()) return remoteProviderIds

        return when (provider) {
            ProviderId.TMDB -> remoteProviderIds.copy(tmdb = remoteProviderIds.tmdb ?: id)
            ProviderId.TVDB -> remoteProviderIds.copy(tvdb = remoteProviderIds.tvdb ?: id)
            ProviderId.KITSU -> remoteProviderIds.copy(kitsu = remoteProviderIds.kitsu ?: id)
            ProviderId.IMDB -> remoteProviderIds.copy(imdb = remoteProviderIds.imdb ?: id)
            ProviderId.TRAKT -> remoteProviderIds.copy(trakt = remoteProviderIds.trakt ?: id)
            ProviderId.SIMKL -> remoteProviderIds.copy(simkl = remoteProviderIds.simkl ?: id)
            else -> remoteProviderIds
        }
    }

    private fun Map<String, Set<String>>.firstValueFor(providerKey: String): String? =
        entries.firstOrNull { it.key.equals(providerKey, ignoreCase = true) }
            ?.value
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()

    private fun ResolvedMetadataDocument.toResolvedDisplayFields(): ResolvedDisplayFields =
        ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = releaseDate.parseYearPrefix(),
            releaseDate = releaseDate,
            overview = overview,
            genres = genres,
            runtimeText = runtimeMinutes?.let { "$it min" }
        )

    private fun String?.parseYearPrefix(): Int? {
        val value = this ?: return null
        if (value.length < 4) return null
        val prefix = value.take(4)
        if (!prefix.all { it.isDigit() }) return null

        return prefix.toIntOrNull()
    }

    private fun MetadataResolutionResult.displayArtwork(): ArtworkBundle {
        val fallbackArtwork = displayMetadata.artwork
        val merged = ArtworkBundle(
            poster = resolvedDocument.artwork.poster ?: fallbackArtwork?.poster,
            backdrop = resolvedDocument.artwork.backdrop ?: fallbackArtwork?.backdrop,
            logo = resolvedDocument.artwork.logo ?: fallbackArtwork?.logo,
            thumbnail = resolvedDocument.artwork.thumbnail ?: fallbackArtwork?.thumbnail
        )

        return merged.takeIf { it.hasArtwork() } ?: ArtworkBundle()
    }

    private fun ArtworkBundle.hasArtwork(): Boolean =
        poster != null || backdrop != null || logo != null || thumbnail != null

    private fun MetadataResolutionResult.toTitleRating(): TitleRating? {
        val value = when (val rating = resolvedDocument.rating) {
            is Number -> rating.toDouble()
            is String -> rating.toDoubleOrNull()
            else -> return null
        } ?: return null

        return TitleRating(value = value, source = ratingSource())
    }

    private fun MetadataResolutionResult.ratingSource(): TitleRatingSource =
        resolvedDocument.sourceProviders[ResolvedField.RATING].toTitleRatingSource()
            ?: displayMetadata.ratingSource.orDefault()

    private fun MetadataResolutionResult.toTrailerDisplayState(): TrailerDisplayState {
        val fallbackTrailerYtIds = providerRunResult.toFieldValues(ResolvedField.TRAILERS)
            .flatMap(::youtubeIdsFromTrailerValue)
            .distinct()
        return TrailerDisplayState(
            fallbackTrailerYtIds = fallbackTrailerYtIds,
            resolverSource = resolvedDocument.sourceProviders[ResolvedField.TRAILERS],
            lastResolvedAtMs = null
        )
    }

    private fun String?.toTitleRatingSource(): TitleRatingSource? =
        when (val source = this?.trim()?.uppercase()) {
            "TMDB" -> TitleRatingSource.TMDB
            else -> TitleRatingSource.TMDB.takeIf { source?.startsWith("TMDB_") == true }
        }

    private fun ResolvedMetadataDocument.toSourceTrace(): List<HydratedHomeFieldTrace> {
        val fields = (sourceRoles.keys + sourceProviders.keys).distinctBy(ResolvedField::name)

        return fields.map { field ->
            HydratedHomeFieldTrace(
                field = field.name,
                selectedProvider = sourceProviders[field].orEmpty(),
                sourceRole = sourceRoles[field]?.name.orEmpty()
            )
        }
    }

    private fun ProviderPlanRunResult?.toFieldValues(field: ResolvedField): List<Any?> =
        this?.stepResults
            ?.mapNotNull { stepResult -> stepResult.candidate?.fields?.get(field)?.value }
            .orEmpty()

    private fun reviewsFrom(value: Any?): List<com.nexio.tv.domain.model.MetaReview> =
        when (value) {
            is com.nexio.tv.domain.model.MetaReview -> listOf(value)
            is Collection<*> -> value.filterIsInstance<com.nexio.tv.domain.model.MetaReview>()
            else -> emptyList()
        }

    private fun recommendationsFrom(value: Any?): List<com.nexio.tv.domain.model.MetaPreview> =
        when (value) {
            is com.nexio.tv.domain.model.MetaPreview -> listOf(value)
            is Collection<*> -> value.filterIsInstance<com.nexio.tv.domain.model.MetaPreview>()
            else -> emptyList()
        }

    private fun youtubeIdsFromTrailerValue(value: Any?): List<String> =
        when (value) {
            is TrailerResolutionResult.External -> listOfNotNull(value.url.youtubeIdFromUrl())
            is TrailerResolutionResult.Playback -> emptyList()
            is TrailerPlaybackSource -> emptyList()
            is TmdbVideoResult -> listOfNotNull(value.key?.trim()?.takeIf { it.isNotBlank() })
            is String -> listOfNotNull(value.youtubeIdFromUrl() ?: value.trim().takeIf { it.isNotBlank() })
            is Collection<*> -> value.flatMap(::youtubeIdsFromTrailerValue)
            else -> emptyList()
        }

    private fun String.youtubeIdFromUrl(): String? {
        val value = trim()
        if (value.isBlank()) return null
        val watchId = value.substringAfter("v=", missingDelimiterValue = "")
            .substringBefore('&')
            .takeIf { it.isNotBlank() }
        if (watchId != null) return watchId
        return value.substringAfter("youtu.be/", missingDelimiterValue = "")
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
    }
}
