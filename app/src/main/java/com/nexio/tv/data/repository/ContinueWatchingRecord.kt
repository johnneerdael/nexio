package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class ContinueWatchingRecord(
    val profileId: Int,
    val parentId: String,
    val contentId: String,
    // Legacy tracking-provider field. Continue Watching source ownership must use
    // source, trackingIdentity, and resumeIdentities.source instead of this value.
    val provider: TrackingProvider,
    val routingVersion: Int,
    val positionMs: Long,
    val durationMs: Long,
    val episodeContext: EpisodeContext?,
    val clickTimeDisplayMetadata: ContinueWatchingMetadataSnapshot?,
    val source: Source,
    val updatedAt: Long,
    val canonicalKey: ContinueWatchingCanonicalKey? = null,
    val displayIdentity: com.nexio.tv.domain.model.ContentIdentity? = null,
    val streamFetchIdentity: StreamFetchIdentity? = null,
    val trackingIdentity: TrackingIdentity? = null,
    val resumeIdentities: List<ResumeIdentity> = emptyList(),
    val primaryResumeLookupKey: String? = resumeIdentities.firstOrNull()?.lookupKey(),
    val identityConfidence: IdentityConfidence = IdentityConfidence.LOW,
    val identityWarnings: List<String> = emptyList(),
    val languageTag: String? = null,
    // Multi-provider ID bundle used by ContinueWatchingMerger for cross-source dedup.
    // Defaults empty for back-compat; ContinueWatchingIdentityResolver populates it from
    // the StableIdBundle so records arriving via different parentIds (TMDB vs IMDB) merge.
    val idBundle: ContinueWatchingIdBundle = ContinueWatchingIdBundle()
) {
    val resumeLookupKeys: Set<String> = resumeIdentities.map { it.lookupKey() }.toSet()

    init {
        require(profileId > 0) { "ContinueWatchingRecord.profileId must be positive" }
        require(parentId.isNotBlank()) { "ContinueWatchingRecord.parentId must not be blank" }
        require(contentId.isNotBlank()) { "ContinueWatchingRecord.contentId must not be blank" }
        require(positionMs >= 0L) { "ContinueWatchingRecord.positionMs must be >= 0" }
        require(durationMs >= 0L) { "ContinueWatchingRecord.durationMs must be >= 0" }
        require(routingVersion > 0) { "ContinueWatchingRecord.routingVersion must be positive" }
        require(updatedAt > 0L) { "ContinueWatchingRecord.updatedAt must be positive" }
        require(canonicalKey == null || canonicalKey.profileId == profileId) {
            "canonicalKey.profileId must match profileId"
        }
        canonicalKey?.let { key ->
            key.stableKey()
            if (episodeContext == null) {
                require(key.season == null && key.episode == null) {
                    "canonicalKey episode fields must be absent when episodeContext is absent"
                }
            } else {
                require(key.season == episodeContext.season && key.episode == episodeContext.number) {
                    "canonicalKey episode fields must match episodeContext"
                }
            }
        }
        require(languageTag == null || languageTag.isNotBlank()) { "languageTag must not be blank when present" }
        require(primaryResumeLookupKey == null || primaryResumeLookupKey in resumeLookupKeys) {
            "primaryResumeLookupKey must reference one of resumeLookupKeys"
        }
    }

    fun identityKey(): String {
        canonicalKey?.let { return it.stableKey() }
        val episodeKey = episodeContext?.let { "s${it.season}e${it.number}" }
        return if (episodeKey != null) {
            "profile:$profileId:continue-watching:$parentId:$episodeKey"
        } else {
            "profile:$profileId:continue-watching:$parentId"
        }
    }

    data class EpisodeContext(val season: Int, val number: Int) {
        init {
            require(season >= 0) { "EpisodeContext.season must be >= 0" }
            require(number >= 0) { "EpisodeContext.number must be >= 0" }
        }
    }

    enum class Source { LOCAL, SYNTHETIC, REMOTE }
}
