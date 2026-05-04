package com.nexio.tv.core.player

import android.util.Log
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diagnostic-only probe profiling for deterministic autoplay decisions.
 *
 * The diagnostic probes the normal resolver-backed CDN path for the primary
 * stream plus a capped set of fallback candidates.
 * Results are logged only; they never influence autoplay selection.
 */
object ProbeProfilingDiagnostic {
    private const val TAG = "ProbeProfiling"
    private const val DEFAULT_FALLBACK_COUNT = 4
    private const val DEFAULT_PER_PROBE_TIMEOUT_MS = 12_000L

    fun launch(
        scope: CoroutineScope,
        playbackInfo: StreamPlaybackInfo,
        fallbackCount: Int = DEFAULT_FALLBACK_COUNT,
        perProbeTimeoutMs: Long = DEFAULT_PER_PROBE_TIMEOUT_MS
    ): Job {
        return scope.launch(Dispatchers.IO) {
            runCatching {
                run(playbackInfo, fallbackCount, perProbeTimeoutMs)
            }.onFailure { error ->
                Log.w(TAG, "Probe profiling failed", error)
            }
        }
    }

    internal fun buildPlanForTesting(
        playbackInfo: StreamPlaybackInfo,
        fallbackCount: Int
    ): List<ProbeProfilingRequest> = buildProbePlan(playbackInfo, fallbackCount)

    private fun buildProbePlan(
        playbackInfo: StreamPlaybackInfo,
        fallbackCount: Int
    ): List<ProbeProfilingRequest> {
        val primaryUrl = playbackInfo.url?.takeIf { it.isNotBlank() } ?: return emptyList()
        val primaryStreamKey = playbackInfo.streamKey ?: "unknown"
        val primaryHeaders = playbackInfo.headers.orEmpty()
        val primaryAddonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(playbackInfo.addonBaseUrl)
        return buildList {
            add(
                ProbeProfilingRequest(
                    label = "primary_resolve_cdn",
                    streamKey = primaryStreamKey,
                    url = primaryUrl,
                    headers = primaryHeaders,
                    addonHost = primaryAddonHost,
                    routeThroughResolver = true
                )
            )
            playbackInfo.autoPlayFallbackCandidates
                .take(fallbackCount)
                .forEachIndexed { index, candidate ->
                    add(candidate.toProbeRequest(index))
                }
        }
    }

    private fun AutoPlayStreamAlternative.toProbeRequest(index: Int): ProbeProfilingRequest {
        return ProbeProfilingRequest(
            label = "fallback_${index}_resolve_cdn",
            streamKey = streamKey ?: "unknown",
            url = url.orEmpty(),
            headers = headers.orEmpty(),
            addonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(addonBaseUrl),
            routeThroughResolver = true
        )
    }

    private suspend fun run(
        playbackInfo: StreamPlaybackInfo,
        fallbackCount: Int,
        perProbeTimeoutMs: Long
    ) {
        val plan = buildProbePlan(playbackInfo, fallbackCount)
        if (plan.isEmpty()) return
        val primaryStreamKey = playbackInfo.streamKey ?: "unknown"
        val sessionStartedAtMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "PROBE_PROFILING_START primary=$primaryStreamKey fallbackCount=${plan.size.coerceAtLeast(2) - 2} " +
                "perProbeTimeoutMs=$perProbeTimeoutMs"
        )
        coroutineScope {
            val outcomes = plan
                .map { request ->
                    async {
                        runProbe(request, perProbeTimeoutMs)
                    }
                }
                .map { it.await() }
            outcomes.forEach { outcome -> Log.i(TAG, outcome.toLogLine()) }
            val winner = outcomes
                .filter { it.status == "ok" }
                .minByOrNull { it.elapsedMs }
            Log.i(
                TAG,
                "PROBE_PROFILING_DONE primary=$primaryStreamKey totalProbes=${outcomes.size} " +
                    "successCount=${outcomes.count { it.status == "ok" }} " +
                    "winner=${winner?.label ?: "none"} winnerElapsedMs=${winner?.elapsedMs ?: -1} " +
                    "sessionElapsedMs=${System.currentTimeMillis() - sessionStartedAtMs}"
            )
        }
    }

    private suspend fun runProbe(
        request: ProbeProfilingRequest,
        perProbeTimeoutMs: Long
    ): ProbeOutcome {
        if (request.url.isBlank()) {
            return ProbeOutcome(
                label = request.label,
                streamKey = request.streamKey,
                urlSummary = "blank",
                elapsedMs = 0L,
                status = "skipped_no_url"
            )
        }
        val startedAtMs = System.currentTimeMillis()
        val result = withTimeoutOrNull(perProbeTimeoutMs) {
            if (request.routeThroughResolver) {
                FfmpegStreamMetadataProbe.probe(
                    url = request.url,
                    headers = request.headers,
                    addonHost = request.addonHost
                )
            } else {
                FfmpegStreamMetadataProbe.probeRawForDiagnostic(
                    url = request.url,
                    headers = request.headers
                )
            }
        }
        return ProbeOutcome(
            label = request.label,
            streamKey = request.streamKey,
            urlSummary = sanitizeUrl(request.url),
            elapsedMs = System.currentTimeMillis() - startedAtMs,
            status = when {
                result == null -> "timeout_or_failure"
                result.streams.isEmpty() -> "ok_empty_streams"
                else -> "ok"
            },
            streamCount = result?.streams?.size ?: 0
        )
    }

    private fun sanitizeUrl(url: String): String {
        return runCatching {
            val parsed = java.net.URL(url)
            val tail = parsed.path
                .split('/')
                .filter { it.isNotBlank() }
                .takeLast(1)
                .joinToString("/")
            "${parsed.host}/...$tail"
        }.getOrElse { "<unparsable>" }
    }

    internal data class ProbeProfilingRequest(
        val label: String,
        val streamKey: String,
        val url: String,
        val headers: Map<String, String>,
        val addonHost: String?,
        val routeThroughResolver: Boolean
    )

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
                "elapsedMs=$elapsedMs status=$status streamCount=$streamCount url=$urlSummary"
        }
    }
}
