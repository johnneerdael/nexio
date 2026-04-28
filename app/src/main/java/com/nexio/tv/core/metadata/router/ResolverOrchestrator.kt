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
        val localResolvers = mutableSetOf(ResolverType.ADDON_DISPLAY)
        val networkResolvers = mutableSetOf<ResolverType>()

        when (depth) {
            MetadataDepth.PREVIEW -> Unit
            MetadataDepth.DETAIL_CORE -> {
                localResolvers += ResolverType.RATING
                localResolvers += ResolverType.ARTWORK
            }
            MetadataDepth.DETAIL_MEDIA -> {
                networkResolvers += ResolverType.TRAILERS
            }
            MetadataDepth.DETAIL_SECONDARY -> {
                localResolvers += ResolverType.RATING
                localResolvers += ResolverType.ARTWORK
                networkResolvers += ResolverType.REVIEWS
                networkResolvers += ResolverType.RECOMMENDATIONS
                networkResolvers += ResolverType.ORGANIZATION_PERSON
            }
            MetadataDepth.SEASON -> {
                localResolvers += ResolverType.RATING
            }
            MetadataDepth.PLAYER -> {
                networkResolvers += ResolverType.TRACKING
                networkResolvers += ResolverType.SKIP_SEGMENTS
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
