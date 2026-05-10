package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.coalesceWith

data class ContinueWatchingMetadataSnapshot(
    val routingVersion: Int,
    val parentId: String,
    val primaryProvider: MetadataPrimaryProvider,
    val decisionReason: MetadataDecisionReason,
    val clickTimeDisplayMetadata: HomeDisplayMetadata
) {
    companion object {
        const val CURRENT_ROUTING_VERSION = 1

        fun fromRoute(
            route: MetadataRoute,
            clickTimeDisplayMetadata: HomeDisplayMetadata
        ): ContinueWatchingMetadataSnapshot {
            return ContinueWatchingMetadataSnapshot(
                routingVersion = CURRENT_ROUTING_VERSION,
                parentId = route.parentId,
                primaryProvider = route.provider,
                decisionReason = route.reason,
                clickTimeDisplayMetadata = clickTimeDisplayMetadata
            )
        }

        fun shouldReroute(storedRoutingVersion: Int): Boolean {
            return storedRoutingVersion != CURRENT_ROUTING_VERSION
        }

        fun renderDisplayMetadata(
            canonical: HomeDisplayMetadata?,
            clickTime: HomeDisplayMetadata?,
            persistedFallback: HomeDisplayMetadata?
        ): HomeDisplayMetadata {
            return canonical
                ?.coalesceWith(clickTime)
                ?.coalesceWith(persistedFallback)
                ?: clickTime?.coalesceWith(persistedFallback)
                ?: persistedFallback
                ?: HomeDisplayMetadata()
        }
    }
}
