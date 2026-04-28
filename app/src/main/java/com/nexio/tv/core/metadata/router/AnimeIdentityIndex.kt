package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.AnimeIdSource
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.ContentMediaKind
import javax.inject.Inject
import javax.inject.Singleton

enum class AnimeIdScheme { KITSU, MAL, ANILIST, ANIDB, IMDB, TMDB, TVDB, TRAKT, SIMKL, UNKNOWN }

data class ParsedMetadataId(
    val scheme: AnimeIdScheme,
    val value: String,
    val raw: String
)

data class AnimeIdentityMapping(
    val scheme: AnimeIdScheme,
    val value: String,
    val kitsuId: String
) {
    fun normalizedLookup(): AnimeIdentityLookup =
        AnimeIdentityLookup(scheme, normalizeMetadataIdValue(scheme, value))
}

data class AnimeIdentityLookup(
    val scheme: AnimeIdScheme,
    val value: String
)

interface AnimeIdentityIndex {
    suspend fun resolveKitsuId(id: ParsedMetadataId): String?
}

@Singleton
class AssetAnimeIdentityIndex @Inject constructor(
    private val mappingService: AnimeIdMappingService
) : AnimeIdentityIndex {
    override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
        val source = id.scheme.toAnimeIdSource() ?: return null
        val animeId = AnimeStremioId(source = source, value = id.value)
        return mappingService.resolveKitsuId(animeId, ContentMediaKind.SERIES)
            ?: mappingService.resolveKitsuId(animeId, ContentMediaKind.MOVIE)
    }

    private fun AnimeIdScheme.toAnimeIdSource(): AnimeIdSource? =
        when (this) {
            AnimeIdScheme.KITSU -> AnimeIdSource.KITSU
            AnimeIdScheme.MAL -> AnimeIdSource.MAL
            AnimeIdScheme.ANILIST -> AnimeIdSource.ANILIST
            AnimeIdScheme.ANIDB -> AnimeIdSource.ANIDB
            AnimeIdScheme.IMDB -> AnimeIdSource.IMDB
            AnimeIdScheme.TMDB,
            AnimeIdScheme.TVDB,
            AnimeIdScheme.TRAKT,
            AnimeIdScheme.SIMKL -> null
            AnimeIdScheme.UNKNOWN -> null
        }
}

object MetadataIdParser {
    fun parse(rawId: String): ParsedMetadataId {
        val raw = rawId.trim()
        val lower = raw.lowercase()
        return when {
            lower.startsWith("kitsu:") -> prefixed(AnimeIdScheme.KITSU, raw)
            lower.startsWith("mal:") -> prefixed(AnimeIdScheme.MAL, raw)
            lower.startsWith("anilist:") -> prefixed(AnimeIdScheme.ANILIST, raw)
            lower.startsWith("anidb:") -> prefixed(AnimeIdScheme.ANIDB, raw)
            lower.startsWith("imdb:") -> prefixed(AnimeIdScheme.IMDB, raw)
            lower.startsWith("tmdb:") -> prefixed(AnimeIdScheme.TMDB, raw)
            lower.startsWith("tvdb:") -> prefixed(AnimeIdScheme.TVDB, raw)
            lower.startsWith("trakt:") -> prefixed(AnimeIdScheme.TRAKT, raw)
            lower.startsWith("simkl:") -> prefixed(AnimeIdScheme.SIMKL, raw)
            lower.startsWith("tt") -> ParsedMetadataId(AnimeIdScheme.IMDB, normalizeMetadataIdValue(AnimeIdScheme.IMDB, raw), raw)
            else -> ParsedMetadataId(AnimeIdScheme.UNKNOWN, raw, raw)
        }
    }

    private fun prefixed(scheme: AnimeIdScheme, raw: String): ParsedMetadataId {
        val value = raw.substringAfter(":").substringBefore(":")
        return ParsedMetadataId(scheme, normalizeMetadataIdValue(scheme, value), raw)
    }
}

fun normalizeMetadataIdValue(scheme: AnimeIdScheme, value: String): String =
    when (scheme) {
        AnimeIdScheme.KITSU,
        AnimeIdScheme.MAL,
        AnimeIdScheme.ANILIST,
        AnimeIdScheme.ANIDB,
        AnimeIdScheme.IMDB,
        AnimeIdScheme.TMDB,
        AnimeIdScheme.TVDB,
        AnimeIdScheme.TRAKT,
        AnimeIdScheme.SIMKL -> value.trim().lowercase()
        AnimeIdScheme.UNKNOWN -> value.trim()
    }

class InMemoryAnimeIdentityIndex(
    mappings: List<AnimeIdentityMapping> = emptyList()
) : AnimeIdentityIndex {
    val lookups: MutableList<AnimeIdentityLookup> = mutableListOf()
    private val mappingByLookup = mappings.associateBy { it.normalizedLookup() }

    override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
        require(id.scheme in supportedLookupSchemes) {
            "AnimeIdentityIndex cannot resolve provider-native or unknown id scheme: ${id.scheme}"
        }
        val lookup = AnimeIdentityLookup(id.scheme, normalizeMetadataIdValue(id.scheme, id.value))
        lookups += lookup
        return mappingByLookup[lookup]?.kitsuId
    }

    private companion object {
        val supportedLookupSchemes = setOf(
            AnimeIdScheme.MAL,
            AnimeIdScheme.ANILIST,
            AnimeIdScheme.ANIDB,
            AnimeIdScheme.IMDB
        )
    }
}
