package com.nexio.tv.core.metadata.router

enum class AnimeIdScheme { KITSU, MAL, ANILIST, ANIDB, IMDB, TMDB, TVDB, UNKNOWN }

data class ParsedMetadataId(
    val scheme: AnimeIdScheme,
    val value: String,
    val raw: String
)

data class AnimeIdentityMapping(
    val scheme: AnimeIdScheme,
    val value: String,
    val kitsuId: String
)

data class AnimeIdentityLookup(
    val scheme: AnimeIdScheme,
    val value: String
)

interface AnimeIdentityIndex {
    suspend fun resolveKitsuId(id: ParsedMetadataId): String?
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
            lower.startsWith("tt") -> ParsedMetadataId(AnimeIdScheme.IMDB, raw, raw)
            else -> ParsedMetadataId(AnimeIdScheme.UNKNOWN, raw, raw)
        }
    }

    private fun prefixed(scheme: AnimeIdScheme, raw: String): ParsedMetadataId {
        val value = raw.substringAfter(":").substringBefore(":")
        return ParsedMetadataId(scheme, value, raw)
    }
}

class InMemoryAnimeIdentityIndex(
    mappings: List<AnimeIdentityMapping> = emptyList()
) : AnimeIdentityIndex {
    val lookups: MutableList<AnimeIdentityLookup> = mutableListOf()
    private val mappingByLookup = mappings.associateBy { AnimeIdentityLookup(it.scheme, it.value) }

    override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
        require(id.scheme in supportedLookupSchemes) {
            "AnimeIdentityIndex cannot resolve provider-native or unknown id scheme: ${id.scheme}"
        }
        val lookup = AnimeIdentityLookup(id.scheme, id.value)
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
