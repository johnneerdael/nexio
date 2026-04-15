package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculator
import com.nexio.tv.core.tvdb.TvdbSeriesTiming
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject

class TvdbContinueWatchingTimingEnricher @Inject constructor(
    private val tvMetadataRouter: TvMetadataRouter?,
    private val availabilityCalculator: TvdbAirAvailabilityCalculator
) {
    constructor() : this(
        tvMetadataRouter = null,
        availabilityCalculator = TvdbAirAvailabilityCalculator()
    )

    suspend fun enrich(entries: List<TrackingNextUpEntry>): List<TrackingNextUpEntry> {
        val tvMetadataRouter = this.tvMetadataRouter ?: return entries
        return entries.map { entry ->
            if (!entry.contentType.isSeriesLike()) {
                return@map entry
            }

            runCatching {
                val request = TvMetadataRequest(
                    contentId = entry.contentId,
                    fallbackContentId = entry.videoId,
                    contentType = ContentType.SERIES,
                    seasonNumbers = listOf(entry.season)
                )
                val seriesDecision = tvMetadataRouter.fetchEnrichment(request)
                val episodeDecision = tvMetadataRouter.fetchEpisodeEnrichment(request)
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
            }.onFailure {
                Log.w(TAG, "exact_air_time_diagnostic reason=missing_timezone_policy contentId=${entry.contentId}")
            }.getOrElse { entry }
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
