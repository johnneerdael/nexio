package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress

data class ContinueWatchingWatchedAnchor(
    val lookupKeys: Set<String>,
    val season: Int?,
    val episode: Int?,
    val lastWatchedMs: Long
) {
    init {
        require(lookupKeys.isNotEmpty()) { "lookupKeys must not be empty" }
        require(lookupKeys.all { it.isNotBlank() }) { "lookupKeys must not contain blank entries" }
        require((season == null) == (episode == null)) {
            "season and episode must both be present or both absent"
        }
        require(season == null || season > 0) { "season must be positive when present" }
        require(episode == null || episode > 0) { "episode must be positive when present" }
        require(lastWatchedMs >= 0L) { "lastWatchedMs must be non-negative" }
    }
}

object ContinueWatchingCanonicalization {
    fun isMainFeedAiredNextUp(entry: TrackingNextUpEntry, nowMs: Long): Boolean =
        AirDateGate.isStrictlyAired(
            availabilityInstantMs = entry.tvdbAvailabilityInstantMs,
            firstAiredMs = entry.firstAiredMs,
            tmdbAirDate = entry.firstAired,
            nowMs = nowMs
        )

    fun pendingTriggerMs(entry: TrackingNextUpEntry): Long? =
        AirDateGate.pendingTriggerMs(
            firstAiredMs = entry.firstAiredMs,
            availabilityInstantMs = entry.tvdbAvailabilityInstantMs,
            tmdbAirDate = entry.firstAired
        )

    fun watchedAnchorsFromProgress(progressItems: List<WatchProgress>): List<ContinueWatchingWatchedAnchor> {
        val anchors = ArrayList<ContinueWatchingWatchedAnchor>()
        for (i in progressItems.indices) {
            val progress = progressItems[i]
            if (!progress.isCompleted()) continue

            val keys = linkedSetOf<String>()
            keys += lookupKeysForRawContentId(progress.contentId)
            keys += lookupKeysForRawContentId(progress.videoId)
            addTraktLookupKeys(progress, keys)
            if (keys.isEmpty()) continue

            val season = progress.season
            val episode = progress.episode
            if ((season == null) != (episode == null)) continue
            if (season != null && (season <= 0 || episode == null || episode <= 0)) continue

            anchors += ContinueWatchingWatchedAnchor(
                lookupKeys = keys,
                season = season,
                episode = episode,
                lastWatchedMs = progress.lastWatched.coerceAtLeast(0L)
            )
        }
        return anchors
    }

    fun isSuppressedByWatchedAnchors(
        lookupKeys: Set<String>,
        season: Int?,
        episode: Int?,
        updatedAtMs: Long,
        anchors: List<ContinueWatchingWatchedAnchor>
    ): Boolean {
        val normalizedLookupKeys = normalizeLookupKeys(lookupKeys)
        if (normalizedLookupKeys.isEmpty()) return false
        for (i in anchors.indices) {
            val anchor = anchors[i]
            if (!hasLookupOverlap(normalizedLookupKeys, anchor.lookupKeys)) continue
            if (updatedAtMs <= anchor.lastWatchedMs) return true
            if (isSameOrEarlierCoordinate(season, episode, anchor)) return true
        }
        return false
    }

    fun lookupKeysForRawContentId(contentId: String?): Set<String> {
        val raw = contentId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptySet()
        if (raw.isCanonicalImdbTitleId()) {
            return linkedSetOf("imdb:$raw", raw)
        }

        val parentRaw = stripEpisodeSuffix(raw) ?: raw
        val splitParts = parentRaw.split(':')
        val parts = ArrayList<String>(splitParts.size)
        for (i in splitParts.indices) {
            parts += splitParts[i].trim()
        }
        if (parts.isEmpty()) return emptySet()

        val keys = linkedSetOf<String>()
        val first = parts[0]
        if (first in mediaTypePrefixes && parts.size >= 3) {
            val mediaType = normalizeMediaType(first)
            val provider = parts[1]
            val value = providerValue(provider = provider, parts = parts, startIndex = 2)
            addProviderLookupKeys(keys, provider, value, mediaType)
            return keys
        }

        val provider = first
        val value = providerValue(provider = provider, parts = parts, startIndex = 1)
        addProviderLookupKeys(keys, provider, value, mediaType = null)
        return keys
    }

    private fun addTraktLookupKeys(progress: WatchProgress, keys: MutableSet<String>) {
        val mediaType = normalizeMediaType(progress.contentType.lowercase())
        val traktId = progress.traktShowId ?: progress.traktMovieId
        if (traktId != null && traktId > 0) {
            keys += "$mediaType:trakt:$traktId"
            keys += "trakt:$traktId"
        }
    }

    private fun normalizeLookupKeys(lookupKeys: Set<String>): Set<String> {
        val normalized = linkedSetOf<String>()
        for (key in lookupKeys) {
            val trimmed = key.trim().lowercase()
            if (trimmed.isNotBlank()) normalized += trimmed
        }
        return normalized
    }

    private fun hasLookupOverlap(candidateKeys: Set<String>, anchorKeys: Set<String>): Boolean {
        val normalizedAnchorKeys = normalizeLookupKeys(anchorKeys)
        for (key in candidateKeys) {
            if (key in normalizedAnchorKeys) return true
        }
        return false
    }

    private fun isSameOrEarlierCoordinate(
        season: Int?,
        episode: Int?,
        anchor: ContinueWatchingWatchedAnchor
    ): Boolean {
        val candidateSeason = season ?: return false
        val candidateEpisode = episode ?: return false
        val anchorSeason = anchor.season ?: return false
        val anchorEpisode = anchor.episode ?: return false
        return candidateSeason < anchorSeason ||
            (candidateSeason == anchorSeason && candidateEpisode <= anchorEpisode)
    }

    private fun addProviderLookupKeys(
        keys: MutableSet<String>,
        provider: String,
        value: String?,
        mediaType: String?
    ) {
        if (provider !in providerPrefixes || value.isNullOrBlank()) return
        val normalizedValue = when (provider) {
            "imdb" -> value.toCanonicalImdbTitleIdOrNull() ?: return
            else -> value
        }
        if (mediaType != null) keys += "$mediaType:$provider:$normalizedValue"
        keys += "$provider:$normalizedValue"
        if (provider == "imdb") keys += normalizedValue
    }

    private fun providerValue(provider: String, parts: List<String>, startIndex: Int): String? {
        if (provider !in providerPrefixes) return null
        val firstValue = parts.getOrNull(startIndex)?.takeIf { it.isNotBlank() } ?: return null
        if (provider == "tmdb" && firstValue in mediaTypePrefixes) {
            return parts.getOrNull(startIndex + 1)?.takeIf { it.isNotBlank() }
        }
        return firstValue
    }

    private fun normalizeMediaType(value: String): String =
        when (value) {
            "movie" -> "movie"
            else -> "series"
        }

    private fun stripEpisodeSuffix(value: String): String? {
        val suffixStart = value.lastIndexOf(":s")
        if (suffixStart <= 0) return null
        val suffix = value.substring(suffixStart + 2)
        val eIndex = suffix.indexOf('e')
        if (eIndex <= 0 || eIndex == suffix.lastIndex) return null
        if (!suffix.substring(0, eIndex).all(Char::isDigit)) return null
        if (!suffix.substring(eIndex + 1).all(Char::isDigit)) return null
        return value.substring(0, suffixStart).takeIf { it.isNotBlank() }
    }

    private fun String.isCanonicalImdbTitleId(): Boolean = matches(imdbTitleIdRegex)

    private fun String.toCanonicalImdbTitleIdOrNull(): String? =
        takeIf { it.isCanonicalImdbTitleId() }

    private val mediaTypePrefixes = setOf("series", "show", "tv", "movie", "anime")
    private val providerPrefixes = setOf(
        "imdb",
        "tmdb",
        "tvdb",
        "trakt",
        "simkl",
        "kitsu",
        "mal",
        "anilist",
        "anidb"
    )
    private val imdbTitleIdRegex = Regex("^tt\\d+$")
}
