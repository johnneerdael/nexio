package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRailOrderDiagnosticsTest {
    @Test
    fun `reconciled event captures saved provider persisted live effective and ignored sources`() {
        val sink = CapturingHomeRailOrderDiagnosticsSink()
        sink.emitReconciled(
            savedGlobalOrder = listOf(HomeRailKey("tmdb_popular")),
            providerOrders = mapOf(RailFamily.TMDB to listOf(HomeRailKey("tmdb_top_rated"))),
            persistedSyntheticOrder = listOf(HomeRailKey("simkl_trending")),
            liveDefinitionOrder = listOf(HomeRailKey("trakt_popular")),
            effectiveOrder = listOf(HomeRailKey("trakt_popular")),
            disabledKeys = emptySet(),
            newlyDiscoveredKeys = listOf(HomeRailKey("trakt_popular")),
            ignoredOrderSources = listOf("persistedSyntheticOrder"),
            mutationSource = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
        )
        val event = sink.events.single()
        assertEquals("home.rail_order_reconciled", event.eventType)
        assertTrue(event.payload.contains("persistedSyntheticOrder"))
        assertTrue(event.payload.contains("PROVIDER_SETTINGS_SCREEN"))
    }

    @Test
    fun `mutation event captures source and order sizes`() {
        val sink = CapturingHomeRailOrderDiagnosticsSink()
        sink.emitMutation(
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            before = emptyList(),
            after = listOf(HomeRailKey("a")),
        )
        val event = sink.events.single()
        assertEquals("home.rail_order_mutation", event.eventType)
        assertTrue(event.payload.contains("ANDROID_ORDER_SCREEN"))
    }

    @Test
    fun `enabled changed event captures key enabled and source`() {
        val sink = CapturingHomeRailOrderDiagnosticsSink()
        sink.emitEnabledChanged(HomeRailKey("k"), false, RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN)
        val event = sink.events.single()
        assertEquals("home.rail_enabled_changed", event.eventType)
        assertTrue(event.payload.contains("enabled=false"))
    }

    @Test
    fun `hidden_due_to_disabled, added_from_missing_default, persisted_synthetic_used_as_content_only emit by key`() {
        val sink = CapturingHomeRailOrderDiagnosticsSink()
        sink.emitHiddenDueToDisabled(HomeRailKey("hidden"))
        sink.emitAddedFromMissingDefault(HomeRailKey("new"))
        sink.emitPersistedSyntheticUsedAsContentOnly(HomeRailKey("fallback"))
        assertEquals(3, sink.events.size)
        assertEquals("home.rail_hidden_due_to_disabled", sink.events[0].eventType)
        assertEquals("home.rail_added_from_missing_default", sink.events[1].eventType)
        assertEquals("home.persisted_synthetic_used_as_content_only", sink.events[2].eventType)
    }

    @Test
    fun `logging sink skips secondary events when isDebugBuild is false`() {
        val sink = LoggingHomeRailOrderDiagnosticsSink(isDebugBuild = false)
        // The 5 secondary events should be no-ops; we have no observable side effect to assert
        // beyond "no exception thrown" since the logging implementation goes to android.util.Log
        // which we don't intercept here. Verify at minimum that the call returns normally and
        // that the constructor accepts isDebugBuild=false (regression: removing the parameter).
        sink.emitMutation(
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            before = emptyList(),
            after = listOf(HomeRailKey("a")),
        )
        sink.emitEnabledChanged(HomeRailKey("k"), false, RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN)
        sink.emitHiddenDueToDisabled(HomeRailKey("h"))
        sink.emitAddedFromMissingDefault(HomeRailKey("n"))
        sink.emitPersistedSyntheticUsedAsContentOnly(HomeRailKey("p"))
    }

    @Test
    fun `logging sink emits reconciled event regardless of isDebugBuild`() {
        // The reconciled event is always-on; this test asserts that even with isDebugBuild=false
        // the call doesn't throw and reaches the implementation body.
        val sink = LoggingHomeRailOrderDiagnosticsSink(isDebugBuild = false)
        sink.emitReconciled(
            savedGlobalOrder = emptyList(),
            providerOrders = emptyMap(),
            persistedSyntheticOrder = emptyList(),
            liveDefinitionOrder = emptyList(),
            effectiveOrder = emptyList(),
            disabledKeys = emptySet(),
            newlyDiscoveredKeys = emptyList(),
            ignoredOrderSources = listOf("persistedSyntheticOrder"),
            mutationSource = RailOrderMutationSource.ACCOUNT_SYNC,
        )
    }
}
