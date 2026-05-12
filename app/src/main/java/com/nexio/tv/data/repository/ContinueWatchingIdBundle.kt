package com.nexio.tv.data.repository

/**
 * Multi-provider ID snapshot for a Continue Watching record.
 *
 * Replaces the opaque single-string [ContinueWatchingRecord.identityKey] for cross-source
 * dedup. Two records refer to the same item if their bundles share at least one non-null
 * ID under the same provider key AND their (season, episode) coordinates match.
 *
 * Priority order for canonical-key selection: IMDB → TMDB → TVDB → Kitsu → MAL → AniList →
 * AniDB → Trakt → SIMKL. Matches Jellyfin's `IsMatch()` fallback chain and CrossWatch's
 * `KEY_PRIORITY` ordering.
 */
data class ContinueWatchingIdBundle(
    val imdb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val kitsu: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
    val trakt: String? = null,
    val simkl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /**
     * True if both bundles refer to the same item: at least one non-null ID shares the same
     * provider key with an equal value AND the (season, episode) tuple matches.
     */
    fun matches(other: ContinueWatchingIdBundle): Boolean {
        if (season != other.season || episode != other.episode) return false
        val a = idMap()
        val b = other.idMap()
        if (a.isEmpty() || b.isEmpty()) return false
        for ((provider, value) in a) {
            val otherValue = b[provider] ?: continue
            if (value == otherValue) return true
        }
        return false
    }

    /**
     * Returns "provider:value" in priority order, or null when no ID is set.
     * Used as a deterministic display key by surfaces that need a single label.
     */
    fun priorityKey(): String? {
        imdb?.let { return "imdb:$it" }
        tmdb?.let { return "tmdb:$it" }
        tvdb?.let { return "tvdb:$it" }
        kitsu?.let { return "kitsu:$it" }
        mal?.let { return "mal:$it" }
        anilist?.let { return "anilist:$it" }
        anidb?.let { return "anidb:$it" }
        trakt?.let { return "trakt:$it" }
        simkl?.let { return "simkl:$it" }
        return null
    }

    /**
     * Bucket keys for union-find dedup in [ContinueWatchingMerger]. One entry per non-null
     * ID, with season/episode appended so episode bundles bucket separately from each other.
     */
    fun toBucketKeys(): List<String> {
        val suffix = if (season != null && episode != null) ":s${season}e${episode}" else ""
        return buildList {
            imdb?.let { add("imdb:$it$suffix") }
            tmdb?.let { add("tmdb:$it$suffix") }
            tvdb?.let { add("tvdb:$it$suffix") }
            kitsu?.let { add("kitsu:$it$suffix") }
            mal?.let { add("mal:$it$suffix") }
            anilist?.let { add("anilist:$it$suffix") }
            anidb?.let { add("anidb:$it$suffix") }
            trakt?.let { add("trakt:$it$suffix") }
            simkl?.let { add("simkl:$it$suffix") }
        }
    }

    private fun idMap(): Map<String, String> = buildMap {
        imdb?.let { put("imdb", it) }
        tmdb?.let { put("tmdb", it) }
        tvdb?.let { put("tvdb", it) }
        kitsu?.let { put("kitsu", it) }
        mal?.let { put("mal", it) }
        anilist?.let { put("anilist", it) }
        anidb?.let { put("anidb", it) }
        trakt?.let { put("trakt", it) }
        simkl?.let { put("simkl", it) }
    }
}
