package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceValidationRulesTest {

    private fun envelope(eventType: String, sequence: Long, payload: Map<String, Any?>) =
        TraceEventEnvelope(
            traceSessionId = "s",
            sequence = sequence,
            wallClockMs = 1L,
            elapsedRealtimeMs = 1L,
            threadName = "t",
            eventType = eventType,
            payload = payload
        )

    private fun assertFires(rule: TraceValidationRule, events: List<TraceEventEnvelope<*>>, expectedRuleId: String? = null) {
        val failures = rule.apply(events)
        assertTrue("rule ${rule.id} should fire on $events", failures.isNotEmpty())
        if (expectedRuleId != null) assertEquals(expectedRuleId, failures.first().ruleId)
    }

    private fun assertSilent(rule: TraceValidationRule, events: List<TraceEventEnvelope<*>>) {
        val failures = rule.apply(events)
        assertTrue("rule ${rule.id} should be silent on $events but got $failures", failures.isEmpty())
    }

    @Test
    fun `PreviewMustNotRouteOrNetwork fires when first_paint has routerExecuted=true`() {
        val rule = TraceValidationRules.PreviewMustNotRouteOrNetwork
        assertFires(rule, listOf(envelope("metadata.first_paint", 1L, mapOf(
            "source" to "ADDON_META_PREVIEW", "routerExecuted" to true, "networkExecuted" to false
        ))))
        assertSilent(rule, listOf(envelope("metadata.first_paint", 1L, mapOf(
            "source" to "ADDON_META_PREVIEW", "routerExecuted" to false, "networkExecuted" to false
        ))))
    }

    @Test
    fun `RouteDecisionUsedInputs fires when usedInputs contains forbidden token`() {
        val rule = TraceValidationRules.RouteDecisionUsedInputs
        val bad = mapOf("usedInputs" to listOf("item.id", "catalog.type"))
        assertFires(rule, listOf(envelope("metadata.route_decision", 1L, bad)))
        val ok = mapOf("usedInputs" to listOf("item.id", "AnimeIdentityIndex"))
        assertSilent(rule, listOf(envelope("metadata.route_decision", 1L, ok)))
    }

    @Test
    fun `FreshCacheHitSuppressesNetwork fires when http_request follows HIT for same op`() {
        val rule = TraceValidationRules.FreshCacheHitSuppressesNetwork
        val events = listOf(
            envelope("runtime.cache_decision", 1L, mapOf("decision" to "HIT", "runtimeOperationId" to "op_1")),
            envelope("http.request", 2L, mapOf("runtimeOperationId" to "op_1"))
        )
        assertFires(rule, events)
        // No fire when MISS_THEN_NETWORK precedes
        assertSilent(rule, listOf(
            envelope("runtime.cache_decision", 1L, mapOf("decision" to "MISS_THEN_NETWORK", "runtimeOperationId" to "op_2")),
            envelope("http.request", 2L, mapOf("runtimeOperationId" to "op_2"))
        ))
    }

    @Test
    fun `RuntimeCallHasApiShapeId fires when operation_start lacks apiShapeId`() {
        val rule = TraceValidationRules.RuntimeCallHasApiShapeId
        assertFires(rule, listOf(envelope("runtime.operation_start", 1L, mapOf("apiShapeId" to ""))))
        assertSilent(rule, listOf(envelope("runtime.operation_start", 1L, mapOf("apiShapeId" to "tmdb.movie.core"))))
    }

    @Test
    fun `NetworkCallHasRuntimeOperationId fires when http_request lacks runtimeOperationId`() {
        val rule = TraceValidationRules.NetworkCallHasRuntimeOperationId
        assertFires(rule, listOf(envelope("http.request", 1L, mapOf("url" to "x"))))
        assertSilent(rule, listOf(envelope("http.request", 1L, mapOf("url" to "x", "runtimeOperationId" to "op_1"))))
    }

    @Test
    fun `ProfileBoundCallHasProfileHash fires when Profile-scoped boundary check lacks profileHash`() {
        val rule = TraceValidationRules.ProfileBoundCallHasProfileHash
        assertFires(rule, listOf(envelope("profile.boundary_check", 1L, mapOf(
            "scope" to "Profile", "verdict" to "PASS", "profileHash" to null
        ))))
        assertSilent(rule, listOf(envelope("profile.boundary_check", 1L, mapOf(
            "scope" to "Profile", "verdict" to "PASS", "profileHash" to "ph_abc"
        ))))
    }

    @Test
    fun `AccountBoundCallHasCredentialHash fires when Account scope lacks credentialTraceHash`() {
        val rule = TraceValidationRules.AccountBoundCallHasCredentialHash
        assertFires(rule, listOf(envelope("profile.boundary_check", 1L, mapOf(
            "scope" to "Account", "verdict" to "PASS", "credentialTraceHash" to null
        ))))
        assertSilent(rule, listOf(envelope("profile.boundary_check", 1L, mapOf(
            "scope" to "Account", "verdict" to "PASS", "credentialTraceHash" to "ch_xyz"
        ))))
    }

    @Test
    fun `GlobalKeyHasNoProfile fires when global cache_decision key contains profile`() {
        val rule = TraceValidationRules.GlobalKeyHasNoProfile
        // operation_start with Global scope, then cache_decision with profile in key
        assertFires(rule, listOf(
            envelope("runtime.operation_start", 1L, mapOf(
                "runtimeOperationId" to "op_1", "scope" to "GlobalContent"
            )),
            envelope("runtime.cache_decision", 2L, mapOf(
                "runtimeOperationId" to "op_1", "cacheKey" to "metadata:profile:1:resolved"
            ))
        ))
        assertSilent(rule, listOf(
            envelope("runtime.operation_start", 1L, mapOf(
                "runtimeOperationId" to "op_2", "scope" to "GlobalContent"
            )),
            envelope("runtime.cache_decision", 2L, mapOf(
                "runtimeOperationId" to "op_2", "cacheKey" to "metadata:tmdb:550"
            ))
        ))
    }

    @Test
    fun `ImageKeyUsesEnglish fires when image cache key has non-en language`() {
        val rule = TraceValidationRules.ImageKeyUsesEnglish
        assertFires(rule, listOf(envelope("runtime.cache_decision", 1L, mapOf(
            "scope" to "GlobalEnglishImage", "cacheKey" to "artwork:tmdb:550:lang:nl"
        ))))
        assertSilent(rule, listOf(envelope("runtime.cache_decision", 1L, mapOf(
            "scope" to "GlobalEnglishImage", "cacheKey" to "artwork:tmdb:550:imageLang:en"
        ))))
    }

    @Test
    fun `IdentityResolutionPrecedesProviderConflict fires when plan needs identity but no resolution event`() {
        val rule = TraceValidationRules.IdentityResolutionPrecedesProviderConflict
        // provider_plan with requiresIdentityResolution=true but no preceding identity_resolution event
        val planEvent = envelope("metadata.provider_plan", 2L, mapOf(
            "contentId" to "tmdb:1399",
            "steps" to listOf(mapOf("requiresIdentityResolution" to true))
        ))
        assertFires(rule, listOf(planEvent))
        // Add identity_resolution before plan should be silent
        assertSilent(rule, listOf(
            envelope("metadata.identity_resolution", 1L, mapOf("sourceId" to "tmdb:1399")),
            planEvent
        ))
    }

    @Test
    fun `FieldHasOwnershipRule fires when field_selected lacks ownershipRule`() {
        val rule = TraceValidationRules.FieldHasOwnershipRule
        assertFires(rule, listOf(envelope("metadata.field_selected", 1L, mapOf(
            "field" to "TITLE", "selectedProvider" to "TMDB"
        ))))
        assertSilent(rule, listOf(envelope("metadata.field_selected", 1L, mapOf(
            "field" to "TITLE", "selectedProvider" to "TMDB", "ownershipRule" to "primary always wins"
        ))))
    }

    @Test
    fun `SecondaryDoesNotOverwritePrimary silent for poster, fires for title overwrite`() {
        val rule = TraceValidationRules.SecondaryDoesNotOverwritePrimary
        // Title selected by secondary while primary was rejected — fires
        assertFires(rule, listOf(envelope("metadata.field_selected", 1L, mapOf(
            "field" to "TITLE",
            "selectedProvider" to "TVDB",
            "rejectedCandidates" to listOf(mapOf("provider" to "TMDB", "reason" to "x"))
        ))))
        // Same shape for POSTER — silent (poster is not in the protected set)
        assertSilent(rule, listOf(envelope("metadata.field_selected", 1L, mapOf(
            "field" to "POSTER",
            "selectedProvider" to "TVDB",
            "rejectedCandidates" to listOf(mapOf("provider" to "TMDB", "reason" to "x"))
        ))))
    }

    @Test
    fun `TraktSimklUsesCorrectProfile fires when boundary_check verdict FAIL for Trakt`() {
        val rule = TraceValidationRules.TraktSimklUsesCorrectProfile
        assertFires(rule, listOf(envelope("profile.boundary_check", 1L, mapOf(
            "provider" to "TRAKT", "verdict" to "FAIL"
        ))))
        assertSilent(rule, listOf(envelope("profile.boundary_check", 1L, mapOf(
            "provider" to "TRAKT", "verdict" to "PASS"
        ))))
    }

    @Test
    fun `NoStaleProfileWritesAfterSwitch fires when CW write follows STALE_SESSION_WRITE_REJECTED for old profile`() {
        val rule = TraceValidationRules.NoStaleProfileWritesAfterSwitch
        assertFires(rule, listOf(
            envelope("profile.boundary_check", 1L, mapOf(
                "verdict" to "FAIL", "violation" to "STALE_SESSION_WRITE_REJECTED",
                "profileHash" to "ph_old"
            )),
            envelope("continue_watching.snapshot_write", 2L, mapOf("profileHash" to "ph_old"))
        ))
        assertSilent(rule, listOf(
            envelope("profile.boundary_check", 1L, mapOf(
                "verdict" to "FAIL", "violation" to "STALE_SESSION_WRITE_REJECTED",
                "profileHash" to "ph_old"
            )),
            envelope("continue_watching.snapshot_write", 2L, mapOf("profileHash" to "ph_new"))
        ))
    }

    @Test
    fun `ALL contains exactly 15 rules`() {
        assertEquals(15, TraceValidationRules.ALL.size)
    }
}
