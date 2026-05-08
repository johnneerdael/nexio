package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

data class StreamSourceContext(
    val mediaKind: MetadataMediaKind,
    val resumeVideoId: String?
)

/**
 * Phase 0 stream policy: default Stremio-style addons fetch series streams by
 * IMDb episode id (`tt...:season:episode`). Addon-specific stream id support
 * belongs in a later AddonStreamIdPolicy, not in this P0 resolver.
 */
@Singleton
class StreamFetchIdentityResolver @Inject constructor() {
    suspend fun resolveForEpisode(
        canonicalIdentity: ContentIdentity,
        knownIds: ProviderIds,
        season: Int,
        episode: Int,
        sourceContext: StreamSourceContext
    ): StreamFetchIdentity? {
        require(season > 0) { "season must be positive" }
        require(episode > 0) { "episode must be positive" }

        val imdbId = knownIds.imdb?.takeIf { it.isStrictImdbTitleId() } ?: return null
        val videoId = "$imdbId:$season:$episode"
        return StreamFetchIdentity(
            contentId = imdbId,
            videoId = videoId,
            idScheme = StreamIdScheme.IMDB_EPISODE,
            confidence = IdentityConfidence.HIGH,
            trace = listOf(
                "phase0 default Stremio stream shape resolved series stream id from IMDb parent id",
                sourceContext.traceDescription(canonicalIdentity)
            )
        )
    }

    suspend fun resolveForMovie(
        canonicalIdentity: ContentIdentity,
        knownIds: ProviderIds,
        sourceContext: StreamSourceContext
    ): StreamFetchIdentity? {
        val imdbId = knownIds.imdb?.takeIf { it.isStrictImdbTitleId() } ?: return null
        return StreamFetchIdentity(
            contentId = imdbId,
            videoId = imdbId,
            idScheme = StreamIdScheme.IMDB_MOVIE,
            confidence = IdentityConfidence.HIGH,
            trace = listOf(
                "phase0 default Stremio stream shape resolved movie stream id from IMDb title id",
                sourceContext.traceDescription(canonicalIdentity)
            )
        )
    }

    private fun StreamSourceContext.traceDescription(canonicalIdentity: ContentIdentity): String {
        val canonicalProvider = canonicalIdentity.canonicalProvider?.name ?: "unknown"
        val canonicalId = canonicalIdentity.canonicalId ?: "unknown"
        val resumeId = resumeVideoId ?: "none"
        return "source mediaKind=$mediaKind canonical=$canonicalProvider:$canonicalId resumeVideoId=$resumeId"
    }

    private fun String.isStrictImdbTitleId(): Boolean =
        STRICT_IMDB_TITLE_ID.matches(this)

    private companion object {
        val STRICT_IMDB_TITLE_ID = Regex("^tt\\d+$")
    }
}
