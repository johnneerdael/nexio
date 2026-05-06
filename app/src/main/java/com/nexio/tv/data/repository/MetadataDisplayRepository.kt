package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
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
            rating = resolvedDocument.rating.toTitleRating(),
            trailer = TrailerDisplayState(),
            seasons = emptyList(),
            people = resolvedDocument.castMembers
                .takeIf { it.isNotEmpty() }
                ?.let { PeopleDisplay(cast = it, crew = emptyList()) },
            reviews = emptyList(),
            recommendations = emptyList(),
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
            providerIds = providerIdsFor(provider, id)
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

    private fun providerIdsFor(provider: ProviderId?, id: String?): ProviderIds {
        if (id.isNullOrBlank()) return ProviderIds()

        return when (provider) {
            ProviderId.TMDB -> ProviderIds(tmdb = id)
            ProviderId.TVDB -> ProviderIds(tvdb = id)
            ProviderId.KITSU -> ProviderIds(kitsu = id)
            ProviderId.IMDB -> ProviderIds(imdb = id)
            else -> ProviderIds()
        }
    }

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

    private fun MetadataResolutionResult.displayArtwork(): ArtworkBundle =
        resolvedDocument.artwork.takeIf { it.hasArtwork() }
            ?: displayMetadata.artwork
            ?: ArtworkBundle()

    private fun ArtworkBundle.hasArtwork(): Boolean =
        poster != null || backdrop != null || logo != null || thumbnail != null

    private fun Any?.toTitleRating(): TitleRating? {
        val value = when (this) {
            is Number -> toDouble()
            else -> return null
        }

        return TitleRating(value = value, source = TitleRatingSource.IMDB)
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
}
