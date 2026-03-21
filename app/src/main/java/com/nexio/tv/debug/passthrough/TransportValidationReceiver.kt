package com.nexio.tv.debug.passthrough

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexio.tv.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TransportValidationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsStore: TransportValidationSettingsStore

    @Inject
    lateinit var playbackLauncher: TransportValidationPlaybackLauncher

    @Inject
    lateinit var sessionStore: TransportValidationSessionStore

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (!BuildConfig.IS_DEBUG_BUILD || intent.action != ACTION_DEBUG_PASSTHROUGH_VALIDATION) {
            return
        }

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val action = intent.getStringExtra(EXTRA_ACTION)?.trim()?.lowercase()
                Log.i(TAG, "Received passthrough validation action=$action")
                when (action) {
                    "enable" -> settingsStore.setTransportValidationEnabled(true)
                    "disable" -> settingsStore.setTransportValidationEnabled(false)
                    "sample" -> {
                        settingsStore.setTransportValidationSelectedSampleId(
                            intent.getStringExtra(EXTRA_SAMPLE_NAME)?.trim()
                        )
                    }
                    "capture" -> {
                        intent.getIntExtra(EXTRA_BURST_COUNT, -1)
                            .takeIf { it > 0 }
                            ?.let { count ->
                                settingsStore.setTransportValidationCaptureBurstCount(count)
                            }
                    }
                    "dumps" -> {
                        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                        settingsStore.setTransportValidationBinaryDumpsEnabled(enabled)
                        Log.i(TAG, "Binary dumps enabled=$enabled")
                    }
                    "runtime" -> {
                        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                        settingsStore.setTransportValidationRuntimeValidationEnabled(enabled)
                        Log.i(TAG, "Runtime validation enabled=$enabled")
                    }
                    "runtime_timeout" -> {
                        intent.getIntExtra(EXTRA_DURATION_MS, -1)
                            .takeIf { it > 0 }
                            ?.let { timeoutMs ->
                                settingsStore.setTransportValidationRuntimeStartupTimeoutMs(timeoutMs)
                                Log.i(TAG, "Runtime startup timeout updated timeoutMs=$timeoutMs")
                            }
                    }
                    "runtime_window" -> {
                        intent.getIntExtra(EXTRA_DURATION_MS, -1)
                            .takeIf { it > 0 }
                            ?.let { observationWindowMs ->
                                settingsStore
                                    .setTransportValidationRuntimeObservationWindowMs(
                                        observationWindowMs
                                    )
                                Log.i(
                                    TAG,
                                    "Runtime observation window updated observationWindowMs=$observationWindowMs"
                                )
                            }
                    }
                    "mark_runtime_observation" -> {
                        val observation =
                            TransportValidationOperatorObservation(
                                avrLock =
                                    when (intent.getStringExtra(EXTRA_AVR_LOCK)?.trim()?.lowercase()) {
                                        "good" -> TransportValidationAvrLockQuality.GOOD
                                        "weak" -> TransportValidationAvrLockQuality.WEAK
                                        "none" -> TransportValidationAvrLockQuality.NONE
                                        else -> TransportValidationAvrLockQuality.UNKNOWN
                                    },
                                audioQuality =
                                    when (intent.getStringExtra(EXTRA_AUDIO_QUALITY)?.trim()?.lowercase()) {
                                        "clean" -> TransportValidationAudioQuality.CLEAN
                                        "choppy" -> TransportValidationAudioQuality.CHOPPY
                                        "dropouts" -> TransportValidationAudioQuality.DROPOUTS
                                        else -> TransportValidationAudioQuality.UNKNOWN
                                    },
                                note = intent.getStringExtra(EXTRA_NOTE)?.trim()?.takeIf { it.isNotEmpty() },
                            )
                        sessionStore.recordOperatorObservation(observation)
                        Log.i(TAG, "Recorded runtime observation observation=$observation")
                    }
                    "start" -> {
                        val requestedSampleId = intent.getStringExtra(EXTRA_SAMPLE_NAME)?.trim()
                        Log.i(TAG, "Start action requestedSampleId=${requestedSampleId ?: "null"}")
                        val selectedSampleId =
                            settingsStore.transportValidationSettings.first().selectedSampleId
                        Log.i(TAG, "Start action selectedSampleId=${selectedSampleId ?: "null"}")
                        val sampleId = requestedSampleId?.takeIf { it.isNotEmpty() } ?: selectedSampleId
                        if (sampleId.isNullOrEmpty()) {
                            Log.w(TAG, "Start action ignored because no sample is selected")
                        } else {
                            if (requestedSampleId != sampleId) {
                                settingsStore.setTransportValidationSelectedSampleId(sampleId)
                                Log.i(TAG, "Start action refreshed selected sampleId=$sampleId")
                            }
                            Log.i(TAG, "Start action scheduling async launch sampleId=$sampleId")
                            receiverScope.launch {
                                Log.i(TAG, "Start action launching sampleId=$sampleId")
                                val started = playbackLauncher.launchSelectedSample(sampleId)
                                Log.i(TAG, "Start action sampleId=$sampleId started=$started")
                            }
                        }
                    }
                    "stop" -> {
                        playbackLauncher.stopPlayback()
                        Log.i(TAG, "Stop action dispatched")
                    }
                    "export" -> {
                        settingsStore.incrementTransportValidationExportRequestCount()
                        val outputFile = sessionStore.exportCurrentSession()
                        Log.i(
                            TAG,
                            "Export action result=${outputFile?.absolutePath ?: "null"}"
                        )
                    }
                    "clear" -> {
                        settingsStore.setTransportValidationSelectedSampleId(null)
                        settingsStore.setTransportValidationEnabled(false)
                        settingsStore.setTransportValidationBinaryDumpsEnabled(false)
                        settingsStore.setTransportValidationRuntimeValidationEnabled(true)
                        settingsStore.setTransportValidationRuntimeStartupTimeoutMs(
                            TransportValidationRuntimeDefaults.thresholds.startupTimeoutMs.toInt()
                        )
                        settingsStore.setTransportValidationRuntimeObservationWindowMs(
                            TransportValidationRuntimeDefaults.thresholds.observationWindowMs.toInt()
                        )
                        settingsStore.setTransportValidationComparisonMode(
                            TransportValidationComparisonMode.FULL_BURST_COMPARE
                        )
                        settingsStore.setTransportValidationCaptureMode(
                            TransportValidationCaptureMode.FIRST_N_BURSTS
                        )
                        settingsStore.setTransportValidationCaptureBurstCount(8)
                        sessionStore.clearSession()
                        Log.i(TAG, "Clear action completed")
                    }
                    else -> {
                        Log.w(TAG, "Ignored unsupported passthrough validation action")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DEBUG_PASSTHROUGH_VALIDATION =
            "com.nexio.tv.DEBUG_PASSTHROUGH_VALIDATION"
        const val EXTRA_ACTION = "action"
        const val EXTRA_SAMPLE_NAME = "name"
        const val EXTRA_BURST_COUNT = "bursts"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_DURATION_MS = "ms"
        const val EXTRA_AVR_LOCK = "avr_lock"
        const val EXTRA_AUDIO_QUALITY = "audio_quality"
        const val EXTRA_NOTE = "note"

        private const val TAG = "TransportValidationReceiver"
    }
}
