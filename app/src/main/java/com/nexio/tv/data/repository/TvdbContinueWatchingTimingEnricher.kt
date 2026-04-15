package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculator
import javax.inject.Inject

class TvdbContinueWatchingTimingEnricher @Inject constructor(
    private val tvMetadataRouter: TvMetadataRouter,
    private val availabilityCalculator: TvdbAirAvailabilityCalculator
) {
    suspend fun enrich(entries: List<TrackingNextUpEntry>): List<TrackingNextUpEntry> {
        return entries
    }
}
