package com.nexio.tv.ui.screens.player

import android.util.Log
import com.nexio.tv.core.player.AndroidFrameRateSettings
import com.nexio.tv.core.player.FrameRateUtils
import com.nexio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val AFR_PREFLIGHT_PROBE_TIMEOUT_MS = 6500L

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
) {
    if (currentStreamUrl != url) return

    if (frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        if (currentStreamUrl == url) {
            _uiState.update {
                it.copy(
                    detectedFrameRateRaw = 0f,
                    detectedFrameRate = 0f,
                    detectedFrameRateSource = null,
                    afrProbeRunning = false
                )
            }
        }
        return
    }

    val activity = currentHostActivity()
    if (activity == null) {
        Log.w(PlayerRuntimeController.TAG, "AFR preflight skipped: host activity unavailable")
        return
    }

    if (currentStreamUrl != url) return
    _uiState.update {
        it.copy(
            detectedFrameRateRaw = 0f,
            detectedFrameRate = 0f,
            detectedFrameRateSource = null,
            afrProbeRunning = true
        )
    }

    val probeHeaders = headers.filterKeys { !it.equals("Range", ignoreCase = true) }

    try {
        val detection = withTimeoutOrNull(AFR_PREFLIGHT_PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                FrameRateUtils.detectFrameRateFromSource(
                    context = context,
                    sourceUrl = url,
                    headers = probeHeaders
                )
            }
        }

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed after ${AFR_PREFLIGHT_PROBE_TIMEOUT_MS}ms"
            )
            return
        }

        if (currentStreamUrl != url) {
            return
        }
        _uiState.update {
            it.copy(
                detectedFrameRateRaw = detection.raw,
                detectedFrameRate = detection.snapped,
                detectedFrameRateSource = FrameRateSource.PROBE
            )
        }

        val prefer23976ProbeBias = detection.raw in 23.95f..24.12f
        val allowResolutionSwitch = AndroidFrameRateSettings.canRequestResolutionSwitch(context)
        val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
            activity = activity,
            detectedFps = detection.snapped,
            prefer23976Near24 = prefer23976ProbeBias
        )

        val result = FrameRateUtils.matchFrameRateAndWait(
            activity = activity,
            frameRate = targetFrameRate,
            videoWidth = detection.videoWidth,
            videoHeight = detection.videoHeight,
            resolutionMatchingEnabled = allowResolutionSwitch
        )

        if (result != null && currentStreamUrl == url) {
            _uiState.update {
                it.copy(
                    displayModeInfo = DisplayModeInfo(
                        width = result.appliedMode.physicalWidth,
                        height = result.appliedMode.physicalHeight,
                        refreshRate = result.appliedMode.refreshRate
                    ),
                    showDisplayModeInfo = true
                )
            }
        }
    } finally {
        withContext(NonCancellable) {
            if (currentStreamUrl == url) {
                _uiState.update { it.copy(afrProbeRunning = false) }
            }
        }
    }
}

internal fun PlayerRuntimeController.launchStartupAfrPreflight(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
) {
    startupAfrPreflightJob?.cancel()
    startupAfrPreflightJob = scope.launch {
        runAfrPreflightIfEnabled(
            url = url,
            headers = headers,
            frameRateMatchingMode = frameRateMatchingMode,
            resolutionMatchingEnabled = resolutionMatchingEnabled
        )
    }
}
