package com.nexio.tv.core.trace

object TraceValidationRules {

    private fun map(env: TraceEventEnvelope<*>): Map<*, *> = env.payload as? Map<*, *> ?: emptyMap<Any, Any>()
    private fun fail(rule: TraceValidationRule, env: TraceEventEnvelope<*>, message: String) =
        TraceValidationFailure(ruleId = rule.id, message = message, sequence = env.sequence)

    val PreviewMustNotRouteOrNetwork: TraceValidationRule = object : TraceValidationRule {
        override val id = "PreviewMustNotRouteOrNetwork"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "metadata.first_paint" }
                .filter { e ->
                    val p = map(e)
                    p["source"] == "ADDON_META_PREVIEW" && (p["routerExecuted"] == true || p["networkExecuted"] == true)
                }
                .map { fail(this, it, "preview emitted with routerExecuted/networkExecuted=true") }
    }

    val RouteDecisionUsedInputs: TraceValidationRule = object : TraceValidationRule {
        override val id = "RouteDecisionUsedInputs"
        private val forbidden = setOf("catalog", "addon", "genre", "animeType", "link", "trend")
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "metadata.route_decision" }
                .filter { e ->
                    @Suppress("UNCHECKED_CAST")
                    val used = (map(e)["usedInputs"] as? List<String>) ?: emptyList()
                    used.any { input -> forbidden.any { f -> input.contains(f, ignoreCase = true) } }
                }
                .map { fail(this, it, "usedInputs contains forbidden routing-input token") }
    }

    val FreshCacheHitSuppressesNetwork: TraceValidationRule = object : TraceValidationRule {
        override val id = "FreshCacheHitSuppressesNetwork"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
            val failures = mutableListOf<TraceValidationFailure>()
            val hitOps = mutableSetOf<String>()
            events.forEach { e ->
                val p = map(e)
                when (e.eventType) {
                    "runtime.cache_decision" -> {
                        if (p["decision"] == "HIT") {
                            (p["runtimeOperationId"] as? String)?.let { hitOps += it }
                        }
                    }
                    "http.request" -> {
                        val opId = p["runtimeOperationId"] as? String
                        if (opId != null && opId in hitOps) {
                            failures += fail(this, e, "http.request issued for op=$opId after fresh cache HIT")
                        }
                    }
                }
            }
            return failures
        }
    }

    val SuppressedStaleCacheHitSuppressesNetwork: TraceValidationRule = object : TraceValidationRule {
        override val id = "SuppressedStaleCacheHitSuppressesNetwork"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
            val failures = mutableListOf<TraceValidationFailure>()
            val suppressedStaleHitOps = mutableSetOf<String>()
            events.forEach { e ->
                val p = map(e)
                when (e.eventType) {
                    "runtime.cache_decision" -> {
                        if (p["decision"] == "STALE_HIT" && p["networkSuppressed"] == true) {
                            (p["runtimeOperationId"] as? String)?.let { suppressedStaleHitOps += it }
                        }
                    }
                    "http.request" -> {
                        val opId = p["runtimeOperationId"] as? String
                        if (opId != null && opId in suppressedStaleHitOps) {
                            failures += fail(this, e, "http.request issued for op=$opId after suppressed stale cache STALE_HIT")
                        }
                    }
                }
            }
            return failures
        }
    }

    val RuntimeCallHasApiShapeId: TraceValidationRule = object : TraceValidationRule {
        override val id = "RuntimeCallHasApiShapeId"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "runtime.operation_start" }
                .filter { e -> (map(e)["apiShapeId"] as? String).isNullOrBlank() }
                .map { fail(this, it, "operation_start has blank apiShapeId") }
    }

    val NetworkCallHasRuntimeOperationId: TraceValidationRule = object : TraceValidationRule {
        override val id = "NetworkCallHasRuntimeOperationId"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "http.request" }
                .filter { e -> (map(e)["runtimeOperationId"] as? String).isNullOrBlank() }
                .map { fail(this, it, "http.request lacks runtimeOperationId") }
    }

    val ProfileBoundCallHasProfileHash: TraceValidationRule = object : TraceValidationRule {
        override val id = "ProfileBoundCallHasProfileHash"
        private val profileScopes = setOf("Profile", "ProfileLocal", "Account")
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "profile.boundary_check" }
                .filter { e ->
                    val p = map(e)
                    p["scope"] in profileScopes && (p["profileHash"] as? String).isNullOrBlank()
                }
                .map { fail(this, it, "profile-bound boundary_check lacks profileHash") }
    }

    val AccountBoundCallHasCredentialHash: TraceValidationRule = object : TraceValidationRule {
        override val id = "AccountBoundCallHasCredentialHash"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "profile.boundary_check" }
                .filter { e ->
                    val p = map(e)
                    p["scope"] == "Account" && (p["credentialTraceHash"] as? String).isNullOrBlank()
                }
                .map { fail(this, it, "Account-scoped boundary_check lacks credentialTraceHash") }
    }

    val GlobalKeyHasNoProfile: TraceValidationRule = object : TraceValidationRule {
        override val id = "GlobalKeyHasNoProfile"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
            val globalOps = mutableSetOf<String>()
            val failures = mutableListOf<TraceValidationFailure>()
            events.forEach { e ->
                val p = map(e)
                when (e.eventType) {
                    "runtime.operation_start" -> {
                        val scope = p["scope"] as? String ?: return@forEach
                        if (scope.startsWith("Global")) {
                            (p["runtimeOperationId"] as? String)?.let { globalOps += it }
                        }
                    }
                    "runtime.cache_decision" -> {
                        val opId = p["runtimeOperationId"] as? String
                        val key = p["cacheKey"] as? String ?: return@forEach
                        if (opId in globalOps && key.contains("profile:")) {
                            failures += fail(this, e, "global op=$opId cache key contains 'profile:': $key")
                        }
                    }
                }
            }
            return failures
        }
    }

    val ImageKeyUsesEnglish: TraceValidationRule = object : TraceValidationRule {
        override val id = "ImageKeyUsesEnglish"
        private val nonEnglishLangPattern = Regex("""(?:imageLang|lang):([^:]+)""", RegexOption.IGNORE_CASE)
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "runtime.cache_decision" }
                .filter { e ->
                    val p = map(e)
                    val scope = p["scope"] as? String
                    val key = p["cacheKey"] as? String ?: return@filter false
                    if (scope != "GlobalEnglishImage") return@filter false
                    val match = nonEnglishLangPattern.find(key)
                    val lang = match?.groupValues?.getOrNull(1)?.lowercase()
                    lang != null && lang != "en"
                }
                .map { fail(this, it, "image cache key has non-English language: ${(map(it)["cacheKey"])}") }
    }

    val IdentityResolutionPrecedesProviderConflict: TraceValidationRule = object : TraceValidationRule {
        override val id = "IdentityResolutionPrecedesProviderConflict"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
            var sawIdentity = false
            val failures = mutableListOf<TraceValidationFailure>()
            events.forEach { e ->
                when (e.eventType) {
                    "metadata.identity_resolution" -> sawIdentity = true
                    "metadata.provider_plan" -> {
                        @Suppress("UNCHECKED_CAST")
                        val steps = (map(e)["steps"] as? List<Map<String, Any?>>) ?: emptyList()
                        val needsIdentity = steps.any { it["requiresIdentityResolution"] == true }
                        if (needsIdentity && !sawIdentity) {
                            failures += fail(this, e, "provider_plan requires identity resolution but none preceded")
                        }
                    }
                }
            }
            return failures
        }
    }

    val FieldHasOwnershipRule: TraceValidationRule = object : TraceValidationRule {
        override val id = "FieldHasOwnershipRule"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "metadata.field_selected" }
                .filter { e -> (map(e)["ownershipRule"] as? String).isNullOrBlank() }
                .map { fail(this, it, "field_selected lacks ownershipRule") }
    }

    val SecondaryDoesNotOverwritePrimary: TraceValidationRule = object : TraceValidationRule {
        override val id = "SecondaryDoesNotOverwritePrimary"
        private val protectedFields = setOf("TITLE", "OVERVIEW", "EPISODE_LIST")
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "metadata.field_selected" }
                .filter { e ->
                    val p = map(e)
                    // F2-I-06: only flag events where the winner itself was SECONDARY;
                    // a PRIMARY winner with rejected SECONDARY candidates is not a violation.
                    val sourceRole = (p["sourceRole"] as? String) ?: return@filter false
                    if (sourceRole != "SECONDARY") return@filter false
                    val field = (p["field"] as? String)?.uppercase() ?: return@filter false
                    if (field !in protectedFields) return@filter false
                    @Suppress("UNCHECKED_CAST")
                    val rejected = (p["rejectedCandidates"] as? List<Map<String, Any?>>) ?: emptyList()
                    rejected.isNotEmpty()
                }
                .map { fail(this, it, "secondary overwrote primary on protected field ${map(it)["field"]}") }
    }

    val TraktSimklUsesCorrectProfile: TraceValidationRule = object : TraceValidationRule {
        override val id = "TraktSimklUsesCorrectProfile"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "profile.boundary_check" }
                .filter { e ->
                    val p = map(e)
                    val provider = p["provider"] as? String
                    p["verdict"] == "FAIL" && (provider == "TRAKT" || provider == "SIMKL")
                }
                .map { fail(this, it, "${map(it)["provider"]} boundary_check verdict=FAIL") }
    }

    /**
     * F-B-04 (Task 22): catches drift between `metadata.resolver_schedule` (what the
     * orchestrator scheduled) and `metadata.field_selected` (what the dispatch loop in
     * MetadataRouterFacade actually emitted). If a future change drops a resolver
     * dispatch, this rule fires.
     *
     * Reads both `scheduled` (real shape from [com.nexio.tv.core.metadata.router.ResolverOrchestrator])
     * and `networkResolvers` (subset key used by some fixtures) so the rule is
     * robust to future renames.
     */
    val ScheduledResolversAreDispatched: TraceValidationRule = object : TraceValidationRule {
        override val id = "ScheduledResolversAreDispatched"

        // Each scheduled resolver type must produce at least one `field_selected`
        // event matching one of the listed fields. Local-pass resolvers (ARTWORK,
        // RATING, ADDON_DISPLAY) are emitted by FieldResolver under POSTER/BACKDROP/
        // LOGO, RATING, TITLE; network resolvers emit their own dedicated field name.
        private val resolverTypeToField: Map<String, Set<String>> = mapOf(
            "REVIEWS" to setOf("REVIEWS"),
            "RECOMMENDATIONS" to setOf("RECOMMENDATIONS"),
            "ORGANIZATION_PERSON" to setOf("CAST", "CREW", "ORGANIZATION_LIST"),
            "TRAILERS" to setOf("TRAILERS"),
            "ARTWORK" to setOf("POSTER", "BACKDROP", "LOGO"),
            "RATING" to setOf("RATING"),
            "ADDON_DISPLAY" to setOf("TITLE"),
            "TRACKING" to setOf("TRACKING")
        )

        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
            val scheduleEvents = events.filter { it.eventType == "metadata.resolver_schedule" }
            if (scheduleEvents.isEmpty()) return emptyList()

            val dispatchedFields: Set<String> = events
                .filter { it.eventType == "metadata.field_selected" }
                .mapNotNull { (map(it)["field"] as? String)?.uppercase() }
                .toSet()

            return scheduleEvents.flatMap { e ->
                val payload = map(e)
                @Suppress("UNCHECKED_CAST")
                val scheduled: List<String> = (
                    (payload["scheduled"] as? List<*>)
                        ?: (payload["networkResolvers"] as? List<*>)
                        ?: emptyList<Any?>()
                    ).filterIsInstance<String>()
                val missing = scheduled
                    .map { it.uppercase() }
                    .filter { type ->
                        val expected = resolverTypeToField[type] ?: return@filter false
                        expected.none { it in dispatchedFields }
                    }
                if (missing.isEmpty()) emptyList()
                else listOf(fail(this, e, "scheduled resolvers not dispatched: $missing"))
            }
        }
    }

    /**
     * F-E-02: every metadata.route_decision targeting TVDB / TMDB / Kitsu MUST be followed by
     * exactly one metadata.localization_plan event before the first metadata.provider_plan
     * step in the same session. Routes targeting other providers (e.g. ADDON) are exempt.
     */
    val LocalizationPlanPrecedesProviderSteps: TraceValidationRule = object : TraceValidationRule {
        override val id = "LocalizationPlanPrecedesProviderSteps"

        private val coveredProviders = setOf("TVDB", "TMDB", "KITSU")

        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
            val ordered = events.sortedBy { it.sequence }
            val failures = mutableListOf<TraceValidationFailure>()

            var i = 0
            while (i < ordered.size) {
                val event = ordered[i]
                if (event.eventType == "metadata.route_decision") {
                    val provider = (map(event)["provider"] as? String)?.uppercase()
                    if (provider in coveredProviders) {
                        var sawLocalizationPlan = false
                        var j = i + 1
                        while (j < ordered.size) {
                            val next = ordered[j]
                            if (next.eventType == "metadata.localization_plan") {
                                val planProvider = (map(next)["provider"] as? String)?.uppercase()
                                if (planProvider == provider) sawLocalizationPlan = true
                            } else if (next.eventType == "metadata.provider_plan") {
                                if (!sawLocalizationPlan) {
                                    failures += fail(this, event, "$provider route_decision (seq=${event.sequence}) followed by provider_plan without intervening localization_plan")
                                }
                                break
                            } else if (next.eventType == "metadata.route_decision") {
                                if (!sawLocalizationPlan) {
                                    failures += fail(this, event, "$provider route_decision (seq=${event.sequence}) ended without localization_plan")
                                }
                                break
                            }
                            j++
                        }
                    }
                }
                i++
            }

            return failures
        }
    }

    /**
     * F2-I-05: Every `runtime.cache_decision` with decision=EXPIRED_MISS must carry a String
     * `cacheKey`, a Number `ttlMs`, and a Number `staleWindowMs`.
     */
    val ExpiredMissCacheDecisionShape: TraceValidationRule = object : TraceValidationRule {
        override val id = "ExpiredMissCacheDecisionShape"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "runtime.cache_decision" }
                .filter { e -> map(e)["decision"] == "EXPIRED_MISS" }
                .mapNotNull { e ->
                    val p = map(e)
                    when {
                        p["cacheKey"] !is String -> fail(this, e, "EXPIRED_MISS cache_decision missing cacheKey string")
                        p["ttlMs"] !is Number -> fail(this, e, "EXPIRED_MISS cache_decision missing ttlMs number")
                        p["staleWindowMs"] !is Number -> fail(this, e, "EXPIRED_MISS cache_decision missing staleWindowMs number")
                        else -> null
                    }
                }
    }

    /**
     * F2-I-05: Every `runtime.cache_decision` with decision=WRITE must carry a String `cacheKey`.
     */
    val WriteCacheDecisionShape: TraceValidationRule = object : TraceValidationRule {
        override val id = "WriteCacheDecisionShape"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "runtime.cache_decision" }
                .filter { e -> map(e)["decision"] == "WRITE" }
                .mapNotNull { e ->
                    val p = map(e)
                    if (p["cacheKey"] !is String) fail(this, e, "WRITE cache_decision missing cacheKey string") else null
                }
    }

    /**
     * F2-I-08: Every `metadata.normalizer_warning` must carry String fields `contentId` and `reason`.
     */
    val NormalizerWarningPayloadShape: TraceValidationRule = object : TraceValidationRule {
        override val id = "NormalizerWarningPayloadShape"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "metadata.normalizer_warning" }
                .mapNotNull { e ->
                    val p = map(e)
                    when {
                        p["contentId"] !is String -> fail(this, e, "normalizer_warning missing contentId string")
                        p["reason"] !is String -> fail(this, e, "normalizer_warning missing reason string")
                        else -> null
                    }
                }
    }

    /**
     * F2-I-09: Every `playback.scrobble_rejected` must carry Number fields `envelopeProfileId` and
     * `activeProfileId`, and a String field `reason`.
     */
    val ScrobbleRejectedPayloadShape: TraceValidationRule = object : TraceValidationRule {
        override val id = "ScrobbleRejectedPayloadShape"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> =
            events.filter { it.eventType == "playback.scrobble_rejected" }
                .mapNotNull { e ->
                    val p = map(e)
                    when {
                        p["envelopeProfileId"] !is Number -> fail(this, e, "scrobble_rejected missing envelopeProfileId number")
                        p["activeProfileId"] !is Number -> fail(this, e, "scrobble_rejected missing activeProfileId number")
                        p["reason"] !is String -> fail(this, e, "scrobble_rejected missing reason string")
                        else -> null
                    }
                }
    }

    val NoStaleProfileWritesAfterSwitch: TraceValidationRule = object : TraceValidationRule {
        override val id = "NoStaleProfileWritesAfterSwitch"
        override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
            val staleProfiles = mutableSetOf<String>()
            val failures = mutableListOf<TraceValidationFailure>()
            events.forEach { e ->
                val p = map(e)
                when (e.eventType) {
                    "profile.boundary_check" -> {
                        if (p["verdict"] == "FAIL" && p["violation"] == "STALE_SESSION_WRITE_REJECTED") {
                            (p["profileHash"] as? String)?.let { staleProfiles += it }
                        }
                    }
                    "continue_watching.snapshot_write" -> {
                        val ph = p["profileHash"] as? String
                        if (ph != null && ph in staleProfiles) {
                            failures += fail(this, e, "CW write for stale profile=$ph after stale-session rejection")
                        }
                    }
                }
            }
            return failures
        }
    }

    val ALL: List<TraceValidationRule> = listOf(
        PreviewMustNotRouteOrNetwork,
        RouteDecisionUsedInputs,
        FreshCacheHitSuppressesNetwork,
        SuppressedStaleCacheHitSuppressesNetwork,
        RuntimeCallHasApiShapeId,
        NetworkCallHasRuntimeOperationId,
        ProfileBoundCallHasProfileHash,
        AccountBoundCallHasCredentialHash,
        GlobalKeyHasNoProfile,
        ImageKeyUsesEnglish,
        IdentityResolutionPrecedesProviderConflict,
        FieldHasOwnershipRule,
        SecondaryDoesNotOverwritePrimary,
        TraktSimklUsesCorrectProfile,
        NoStaleProfileWritesAfterSwitch,
        ScheduledResolversAreDispatched,
        LocalizationPlanPrecedesProviderSteps,
        ExpiredMissCacheDecisionShape,
        WriteCacheDecisionShape,
        NormalizerWarningPayloadShape,
        ScrobbleRejectedPayloadShape
    )
}
