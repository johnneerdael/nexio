package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies that provider Retrofit APIs are only referenced within the integration boundary.
 *
 * Allowed paths are either structural (DI wiring, the remote-api definition package itself, the
 * data/integration layer) or explicit auth-service carve-outs documented below.
 *
 * ## Auth-service carve-outs (F2-C-06)
 *
 * Three legacy auth services are exempt from this boundary check because their OAuth flows
 * (password-grant/device-code/PIN-grant + token refresh) run before — or independently of — any
 * [IntegrationRuntime] context:
 *
 * - **KitsuAuthService** — password-grant login and proactive token refresh call [KitsuAuthApi]
 *   directly. The flow predates the runtime and has no session context to inject through it.
 *   Migration plan: when the runtime supports pre-auth or session-less contexts, route through it.
 *
 * - **RealDebridAuthService** — OAuth device-code flow (device-code request → credential poll →
 *   token exchange) and subsequent token refresh call the Real-Debrid Retrofit API directly.
 *   Migration plan: same as Kitsu — route through the runtime once pre-auth contexts land.
 *
 * - **SimklAuthService** — PIN-code grant (pin-code request → PIN-status poll → token save) calls
 *   the SIMKL Retrofit API directly. Migration plan: same as above.
 *
 * Until the runtime gains pre-auth context support (no current ETA), these three entries MUST
 * remain in the allowlist. Do NOT remove them without a corresponding runtime capability.
 *
 * Tracking: review-dossier-2/09-known-gaps.md F2-C-06.
 */
class IntegrationBoundaryTest {

    /**
     * Auth-service carve-outs that are exempt from the Retrofit-usage boundary check.
     *
     * Each entry maps a production-source path suffix to a short justification so that any future
     * reader — or a test failure message — can immediately understand why the exemption exists and
     * what the migration path is.
     *
     * See the class-level KDoc for the full rationale (F2-C-06).
     */
    private val authServiceCarveOuts: Map<String, String> = mapOf(
        "/com/nexio/tv/data/repository/KitsuAuthService.kt" to
            "F2-C-06: password-grant login + proactive token refresh via KitsuAuthApi run before " +
            "any IntegrationRuntime context. Migrate when runtime supports pre-auth contexts.",
        "/com/nexio/tv/data/repository/RealDebridAuthService.kt" to
            "F2-C-06: OAuth device-code flow (device-code → credential poll → token exchange) and " +
            "token refresh predate the runtime. Same migration plan as KitsuAuthService.",
        "/com/nexio/tv/data/repository/SimklAuthService.kt" to
            "F2-C-06: PIN-code grant (pin-code → PIN-status poll → token save) predates the " +
            "runtime. Same migration plan as KitsuAuthService."
    )

    @Test
    fun `non integration packages do not reference provider retrofit apis directly across the whole tree`() {
        val forbiddenTypeNames = productionRemoteApiSurfaceSimpleNames("/com/nexio/tv/data/remote/api/")

        val offenders = productionRegexScan(
            forbiddenPatterns = forbiddenTypeNames.associateWith { Regex("""\b${Regex.escape(it)}\b""") },
            allowedPaths = productionAllowedPathSuffixes(
                // Structural exemptions: DI wiring, Retrofit API definitions, and the
                // integration-provider layer that owns the Retrofit surface.
                "/com/nexio/tv/core/di/",
                "/com/nexio/tv/core/tvdb/TvdbAuthService.kt",
                "/com/nexio/tv/data/integration/",
                "/com/nexio/tv/data/remote/api/",
                // Auth-service carve-outs (F2-C-06) — see authServiceCarveOuts for justifications.
                *authServiceCarveOuts.keys.toTypedArray()
            )
        )

        if (offenders.isNotEmpty()) {
            val carveOutReasons = authServiceCarveOuts.entries
                .joinToString("\n") { (path, reason) -> "  $path => $reason" }
            fail(
                "Direct provider API usage outside integration boundary: $offenders\n" +
                "Note: the following paths are intentional carve-outs (F2-C-06):\n$carveOutReasons"
            )
        }
    }
}
