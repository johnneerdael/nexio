package com.nexio.tv.core.player

import android.util.Log
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diagnostic-only probe profiling. Fires several ffprobe calls in parallel
 * after the deterministic autoplay decision is made, so we can measure:
 *
 *  - **Proxy-vs-CDN race**: probe the addon proxy URL directly (letting
 *    libavformat follow the 302 itself) and the explicit
 *    [CometProxyUrlResolver]-then-CDN path simultaneously. Whichever returns
 *    valid metadata first wins.
 *  - **Parallel fanout**: simultaneously probe the primary candidate plus the
 *    top N fallback candidates. Measures whether the network / CDN pool can
 *    handle concurrent probes within the autoplay budget, and how often a
 *    fallback would beat the primary on cold first-byte time.
 *
 * Pure measurement — never influences autoplay outcome. Invocations are
 * fire-and-forget on the [scope] argument, gated by the
 * `probeProfilingDiagnosticEnabled` setting.
 */
object ProbeProfilingDiagnostic {

    private const val TAG = "ProbeProfiling"
    private const val DEFAULT_FALLBACK_COUNT = 4
    private const val DEFAULT_PER_PROBE_TIMEOUT_MS = 12_000L

    // Long-lived scope decoupled from any ViewModel lifecycle. The diagnostic
    // intentionally outlives the StreamScreen → Player navigation transition so
    // the summary line still fires after autoplay hands off to the player.
    // SupervisorJob so a single failed probe doesn't abort the whole batch.
    private val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget. Runs the parallel probe race in a process-scoped
     * background coroutine and returns the launched [Job]. Caller does not need
     * to await. The [scope] argument is unused now (kept for API stability) —
     * the diagnostic uses its own scope so it survives ViewModel teardown that
     * happens when autoplay transitions to the player screen.
     */
    @Suppress("UNUSED_PARAMETER")
    fun launch(
        scope: CoroutineScope,
        playbackInfo: StreamPlaybackInfo,
        fallbackCount: Int = DEFAULT_FALLBACK_COUNT,
        perProbeTimeoutMs: Long = DEFAULT_PER_PROBE_TIMEOUT_MS
    ): Job {
        return diagnosticScope.launch {
            runCatching { run(playbackInfo, fallbackCount, perProbeTimeoutMs) }
                .onFailure { Log.w(TAG, "Probe profiling failed", it) }
        }
    }

    private suspend fun run(
        playbackInfo: StreamPlaybackInfo,
        fallbackCount: Int,
        perProbeTimeoutMs: Long
    ) {
        val primaryUrl = playbackInfo.url ?: return
        val primaryAddonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(playbackInfo.addonBaseUrl)
        val primaryHeaders = playbackInfo.headers.orEmpty()
        val primaryStreamKey = playbackInfo.streamKey ?: "unknown"
        val fallbacks = playbackInfo.autoPlayFallbackCandidates.take(fallbackCount)

        Log.i(
            TAG,
            "PROBE_PROFILING_START primary=$primaryStreamKey fallbackCount=${fallbacks.size} " +
                "perProbeTimeoutMs=$perProbeTimeoutMs"
        )

        val sessionStartedAtMs = System.currentTimeMillis()
        coroutineScope {
            val primaryResolved = async {
                runProbe(
                    label = "primary_resolve_cdn",
                    streamKey = primaryStreamKey,
                    url = primaryUrl,
                    headers = primaryHeaders,
                    addonHost = primaryAddonHost,
                    routeThroughResolver = true,
                    perProbeTimeoutMs = perProbeTimeoutMs
                )
            }
            val fallbackJobs = fallbacks.mapIndexed { index, candidate ->
                async {
                    val candidateUrl = candidate.url
                    if (candidateUrl.isNullOrBlank()) {
                        ProbeOutcome(
                            label = "fallback_${index}_resolve_cdn",
                            streamKey = candidate.streamKey ?: "unknown",
                            urlSummary = "blank",
                            elapsedMs = 0,
                            status = "skipped_no_url"
                        )
                    } else {
                        runProbe(
                            label = "fallback_${index}_resolve_cdn",
                            streamKey = candidate.streamKey ?: "unknown",
                            url = candidateUrl,
                            headers = candidate.headers.orEmpty(),
                            addonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(candidate.addonBaseUrl),
                            routeThroughResolver = true,
                            perProbeTimeoutMs = perProbeTimeoutMs
                        )
                    }
                }
            }
            val outcomes = listOf(primaryResolved.await()) + fallbackJobs.map { it.await() }
            val sessionElapsedMs = System.currentTimeMillis() - sessionStartedAtMs
            // Per-probe lines are already emitted by runProbe immediately on
            // completion — this final summary just identifies the winner so we
            // can tell at a glance which path was fastest. Use NonCancellable
            // so the summary still fires if outer cancellation is propagated.
            withContext(NonCancellable) {
                val winner = outcomes
                    .filter { it.status == "ok" }
                    .minByOrNull { it.elapsedMs }
                Log.i(
                    TAG,
                    "PROBE_PROFILING_DONE primary=$primaryStreamKey totalProbes=${outcomes.size} " +
                        "successCount=${outcomes.count { it.status == "ok" }} " +
                        "winner=${winner?.label ?: "none"} winnerElapsedMs=${winner?.elapsedMs ?: -1} " +
                        "sessionElapsedMs=$sessionElapsedMs"
                )
            }
        }
    }

    private suspend fun runProbe(
        label: String,
        streamKey: String,
        url: String,
        headers: Map<String, String>,
        addonHost: String?,
        routeThroughResolver: Boolean,
        perProbeTimeoutMs: Long
    ): ProbeOutcome {
        val startedAtMs = System.currentTimeMillis()
        val urlSummary = sanitizeUrl(url)
        val result = withTimeoutOrNull(perProbeTimeoutMs) {
            if (routeThroughResolver) {
                FfmpegStreamMetadataProbe.probe(url = url, headers = headers, addonHost = addonHost)
            } else {
                FfmpegStreamMetadataProbe.probeRawForDiagnostic(url = url, headers = headers)
            }
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val status = when {
            result == null -> "timeout_or_failure"
            result.streams.isEmpty() -> "ok_empty_streams"
            else -> "ok"
        }
        val outcome = ProbeOutcome(
            label = label,
            streamKey = streamKey,
            urlSummary = urlSummary,
            elapsedMs = elapsedMs,
            status = status,
            streamCount = result?.streams?.size ?: 0
        )
        // Emit immediately so per-probe data lands even if the diagnostic's
        // parent scope is cancelled before the summary fires.
        Log.i(TAG, outcome.toLogLine())
        return outcome
    }

    private fun sanitizeUrl(url: String): String {
        return runCatching {
            val parsed = java.net.URL(url)
            val tail = parsed.path.split('/').filter { it.isNotBlank() }.takeLast(1)
            "${parsed.host}/...${tail.joinToString("/")}"
        }.getOrElse { "<unparsable>" }
    }

    private data class ProbeOutcome(
        val label: String,
        val streamKey: String,
        val urlSummary: String,
        val elapsedMs: Long,
        val status: String,
        val streamCount: Int = 0
    ) {
        fun toLogLine(): String {
            return "PROBE_PROFILING_RESULT label=$label stream=$streamKey " +
                "elapsedMs=$elapsedMs status=$status streamCount=$streamCount " +
                "url=$urlSummary"
        }
    }
}
