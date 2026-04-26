package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.domain.model.HomeDisplayMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMetadataRouterTest {

    @Test
    fun `playback snapshot stores route context and click-time display metadata`() {
        val clickTime = HomeDisplayMetadata(
            title = "Addon Title",
            poster = "addon-poster",
            backdrop = "addon-backdrop"
        )
        val route = metadataRoute(
            parentId = "tvdb:121361",
            provider = MetadataPrimaryProvider.TVDB,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES
        )

        val metadataSnapshot = ContinueWatchingMetadataSnapshot.fromRoute(
            route = route,
            clickTimeDisplayMetadata = clickTime
        )
        val snapshot = ContinueWatchingSnapshot(
            metadataSnapshotsByItemKey = mapOf("series:121361" to metadataSnapshot)
        )

        val stored = snapshot.metadataSnapshotsByItemKey.getValue("series:121361")
        assertEquals(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION, stored.routingVersion)
        assertEquals("tvdb:121361", stored.parentId)
        assertEquals(MetadataPrimaryProvider.TVDB, stored.primaryProvider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, stored.decisionReason)
        assertEquals(clickTime, stored.clickTimeDisplayMetadata)
    }

    @Test
    fun `routing version mismatch requires one reroute`() {
        assertTrue(ContinueWatchingMetadataSnapshot.shouldReroute(storedRoutingVersion = 0))
        assertFalse(
            ContinueWatchingMetadataSnapshot.shouldReroute(
                storedRoutingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION
            )
        )
    }

    @Test
    fun `offline render merges canonical then click-time then persisted fallback`() {
        val canonical = HomeDisplayMetadata(
            title = "Canonical Title",
            description = "Canonical Description"
        )
        val clickTime = HomeDisplayMetadata(
            title = "Click Title",
            poster = "click-poster",
            backdrop = "click-backdrop"
        )
        val persistedFallback = HomeDisplayMetadata(
            title = "Persisted Title",
            description = "Persisted Description",
            runtime = "42m",
            poster = "persisted-poster"
        )

        val rendered = ContinueWatchingMetadataSnapshot.renderDisplayMetadata(
            canonical = canonical,
            clickTime = clickTime,
            persistedFallback = persistedFallback
        )

        assertEquals("Canonical Title", rendered.title)
        assertEquals("Canonical Description", rendered.description)
        assertEquals("click-poster", rendered.poster)
        assertEquals("click-backdrop", rendered.backdrop)
        assertEquals("42m", rendered.runtime)
    }

    private fun metadataRoute(
        parentId: String,
        provider: MetadataPrimaryProvider,
        reason: MetadataDecisionReason
    ): MetadataRoute {
        return MetadataRoute(
            provider = provider,
            parentId = parentId,
            mediaKind = MetadataMediaKind.SERIES,
            reason = reason,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(provider to parentId),
            trace = listOf(MetadataRouteTrace(reason, "test route"))
        )
    }
}
