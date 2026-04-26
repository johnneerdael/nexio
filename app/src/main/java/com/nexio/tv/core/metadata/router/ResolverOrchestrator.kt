package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResolverOrchestrator @Inject constructor() {
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
                localResolvers += ResolverType.ARTWORK
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

        return ResolverSchedule(
            depth = depth,
            localResolvers = localResolvers.toList(),
            networkResolvers = networkResolvers.toList()
        )
    }
}
