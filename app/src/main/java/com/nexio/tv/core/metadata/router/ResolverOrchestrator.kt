package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResolverOrchestrator @Inject constructor(
    private val traceEvents: TraceMetadataEvents
) {
    constructor() : this(
        TraceMetadataEvents(
            sink = NoopRuntimeTraceSink,
            sessionId = { null }
        )
    )

    fun schedule(depth: MetadataDepth): ResolverSchedule {
        val localResolvers = if (depth == MetadataDepth.IDENTITY) {
            mutableSetOf()
        } else {
            mutableSetOf(ResolverType.ADDON_DISPLAY)
        }
        val networkResolvers = mutableSetOf<ResolverType>()

        when (depth) {
            MetadataDepth.PREVIEW,
            MetadataDepth.IDENTITY -> Unit
            MetadataDepth.DETAIL_CORE -> {
                localResolvers += ResolverType.RATING
                localResolvers += ResolverType.ARTWORK
            }
            // ARTWORK belongs to DETAIL_CORE only (F-04-04). Backdrop/logo are returned by primary
            // providers in their core response (TMDB *_CORE, TVDB SERIES); a separate DETAIL_MEDIA
            // artwork pass would force a redundant network round-trip with no observable benefit.
            // The pinning tests `DETAIL_MEDIA does not schedule ARTWORK` and
            // `DETAIL_CORE still schedules ARTWORK` enforce this invariant.
            MetadataDepth.DETAIL_MEDIA -> {
                networkResolvers += ResolverType.TRAILERS
            }
            MetadataDepth.DETAIL_SECONDARY,
            MetadataDepth.DETAIL_FULL -> {
                localResolvers += ResolverType.RATING
                localResolvers += ResolverType.ARTWORK
                networkResolvers += ResolverType.REVIEWS
                networkResolvers += ResolverType.RECOMMENDATIONS
                networkResolvers += ResolverType.ORGANIZATION_PERSON
            }
            MetadataDepth.SEASON -> {
                localResolvers += ResolverType.RATING
            }
            // SKIP_SEGMENTS is intentionally omitted from the scheduled metadata pipeline.
            // Player skip segments are resolved by SkipSegmentResolver, with SkipIntroRepository
            // kept behind it as the low-level provider/cache adapter.
            MetadataDepth.PLAYER -> {
                networkResolvers += ResolverType.TRACKING
            }
        }

        val scheduledSet = localResolvers + networkResolvers
        val allTypes = ResolverType.values().toSet()
        val skippedMap = (allTypes - scheduledSet).associate { type ->
            type.name to "depth ${depth.name} not requested"
        }

        traceEvents.emitResolverSchedule(
            depth = depth.name,
            scheduled = scheduledSet.map { it.name },
            skipped = skippedMap
        )

        return ResolverSchedule(
            depth = depth,
            localResolvers = localResolvers.toList(),
            networkResolvers = networkResolvers.toList()
        )
    }
}
