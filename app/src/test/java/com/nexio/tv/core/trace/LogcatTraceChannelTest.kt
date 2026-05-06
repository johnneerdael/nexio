package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogcatTraceChannelTest {

    @Test
    fun `metadata first_paint maps to FIRST_PAINT`() {
        assertEquals(LogcatTraceChannel.FIRST_PAINT, LogcatTraceChannel.forEventType("metadata.first_paint"))
    }

    @Test
    fun `metadata route_decision maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.route_decision"))
    }

    @Test
    fun `metadata identity_resolution maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.identity_resolution"))
    }

    @Test
    fun `metadata provider_plan maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.provider_plan"))
    }

    @Test
    fun `metadata resolver_schedule maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.resolver_schedule"))
    }

    @Test
    fun `metadata field_selected maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.field_selected"))
    }

    @Test
    fun `metadata localization_plan maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.localization_plan"))
    }

    @Test
    fun `metadata normalizer_warning maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.normalizer_warning"))
    }

    @Test
    fun `metadata stable_id_bundle maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.stable_id_bundle"))
    }

    @Test
    fun `home hydration lifecycle maps to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.hydration_started"))
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.hydration_overlay_written"))
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.hydration_applied"))
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.hydration_ignored"))
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.hydration_failed_using_preview"))
    }

    @Test
    fun `runtime operation_start maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("runtime.operation_start"))
    }

    @Test
    fun `runtime cache_decision maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("runtime.cache_decision"))
    }

    @Test
    fun `runtime operation_finish maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("runtime.operation_finish"))
    }

    @Test
    fun `runtime operation_failed maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("runtime.operation_failed"))
    }

    @Test
    fun `http request maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("http.request"))
    }

    @Test
    fun `http response maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("http.response"))
    }

    @Test
    fun `http error maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("http.error"))
    }

    @Test
    fun `trace body_sample maps to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("trace.body_sample"))
    }

    @Test
    fun `artwork materialization events map to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.decision_lookup"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.decision_missing"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.decision_put"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.decision_store_load"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.decision_store_write"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.asset_materialized"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.fallback_materialized"))
    }

    @Test
    fun `home snapshot events map to META_ROUTE`() {
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.snapshot_read"))
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.snapshot_write"))
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("home.snapshot_sanitize_artwork"))
    }

    @Test
    fun `policy events map to null (bundle-only)`() {
        assertNull(LogcatTraceChannel.forEventType("policy.unscoped_network"))
    }

    @Test
    fun `scrobble_rejected maps to null (bundle-only)`() {
        assertNull(LogcatTraceChannel.forEventType("playback.scrobble_rejected"))
    }

    @Test
    fun `profile boundary_check maps to null (bundle-only)`() {
        assertNull(LogcatTraceChannel.forEventType("profile.boundary_check"))
    }

    @Test
    fun `continue_watching events map to null (bundle-only)`() {
        assertNull(LogcatTraceChannel.forEventType("continue_watching.snapshot_write"))
    }

    @Test
    fun `unknown event types map to null`() {
        assertNull(LogcatTraceChannel.forEventType("something.unknown"))
    }

    @Test
    fun `tag returns expected logcat tag for each channel`() {
        assertEquals("Nexio.FirstPaint", LogcatTraceChannel.FIRST_PAINT.tag)
        assertEquals("Nexio.MetaRoute", LogcatTraceChannel.META_ROUTE.tag)
        assertEquals("Nexio.IntRuntime", LogcatTraceChannel.INT_RUNTIME.tag)
    }
}
