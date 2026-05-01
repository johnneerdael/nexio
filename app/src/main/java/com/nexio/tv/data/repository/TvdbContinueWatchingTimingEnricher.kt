package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculator
import com.nexio.tv.core.tvdb.TvdbSeriesTiming
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class TvdbContinueWatchingTimingEnricher @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade?,
    private val availabilityCalculator: TvdbAirAvailabilityCalculator
) {
    constructor() : this(
        metadataRouterFacade = null,
        availabilityCalculator = TvdbAirAvailabilityCalculator()
    )

    suspend fun enrich(entries: List<TrackingNextUpEntry>): List<TrackingNextUpEntry> {
        val metadataRouterFacade = this.metadataRouterFacade ?: return entries
        return entries.map { entry ->
            if (!entry.contentType.isSeriesLike()) {
                return@map entry
            }

            try {
                val request = TvMetadataRequest(
                    contentId = entry.contentId,
                    fallbackContentId = entry.videoId,
                    contentType = ContentType.SERIES,
                    seasonNumbers = listOf(entry.season)
                )
                // Use DETAIL_CORE depth with no seasonNumber for series-level enrichment
                // (airsTime, originalCountry, etc.), and a separate request for episode metadata.
                val metadataRequest = MetadataRequest(
                    contentId = entry.contentId,
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(itemType = entry.contentType),
                    depth = MetadataDepth.DETAIL_CORE
                )
                val episodeMetadataRequest = MetadataRequest(
                    contentId = entry.contentId,
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(itemType = entry.contentType),
                    seasonNumber = entry.season,
                    depth = MetadataDepth.SEASON
                )
                val seriesDecision = metadataRouterFacade.fetchTvEnrichment(metadataRequest, request)
                val episodeDecision = metadataRouterFacade.fetchTvEpisodeEnrichment(episodeMetadataRequest, request)
                val series = seriesDecision.value
                val episodeAiredDate = episodeDecision.value
                    ?.get(entry.season to entry.episode)
                    ?.airDate
                    ?: entry.firstAired
                val availability = availabilityCalculator.computeAvailability(
                    episodeAiredDate = episodeAiredDate,
                    seriesTiming = TvdbSeriesTiming(
                        airsTime = series?.airsTime,
                        originalCountry = series?.originalCountry,
                        originalNetwork = series?.originalNetwork,
                        latestNetwork = series?.latestNetwork,
                        platformName = series?.platformName
                    )
                )

                entry.copy(
                    tvdbAvailabilityInstantMs = availability.instantMs,
                    tvdbAvailabilityPrecision = availability.precision,
                    tvdbAvailabilitySourceZoneId = availability.sourceZoneId,
                    tvdbAvailabilitySourcePolicy = availability.sourcePolicy,
                    tvdbAvailabilityDiagnosticReason = availability.diagnosticReason,
                    tvdbAvailabilityDeviceLocalDateTime = availability.deviceLocalDateTime
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(TAG, "exact_air_time_diagnostic reason=missing_timezone_policy contentId=${entry.contentId}")
                entry
            }
        }
    }

    private fun String.isSeriesLike(): Boolean {
        return equals("series", ignoreCase = true) ||
            equals("tv", ignoreCase = true) ||
            equals("anime", ignoreCase = true)
    }

    private companion object {
        const val TAG = "TvdbAirTiming"
    }
}
