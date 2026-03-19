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
                when (intent.getStringExtra(EXTRA_ACTION)?.trim()?.lowercase()) {
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
                    "start" -> {
                        val sampleId = intent.getStringExtra(EXTRA_SAMPLE_NAME)?.trim().orEmpty()
                        if (sampleId.isNotEmpty()) {
                            settingsStore.setTransportValidationSelectedSampleId(sampleId)
                            playbackLauncher.launchSelectedSample(sampleId)
                        }
                    }
                    "stop" -> playbackLauncher.stopPlayback()
                    "export" -> {
                        settingsStore.incrementTransportValidationExportRequestCount()
                        sessionStore.exportCurrentSession()
                    }
                    "clear" -> {
                        settingsStore.setTransportValidationSelectedSampleId(null)
                        settingsStore.setTransportValidationEnabled(false)
                        settingsStore.setTransportValidationBinaryDumpsEnabled(false)
                        settingsStore.setTransportValidationComparisonMode(
                            TransportValidationComparisonMode.FULL_BURST_COMPARE
                        )
                        settingsStore.setTransportValidationCaptureMode(
                            TransportValidationCaptureMode.FIRST_N_BURSTS
                        )
                        settingsStore.setTransportValidationCaptureBurstCount(8)
                        sessionStore.clearSession()
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

        private const val TAG = "TransportValidationReceiver"
    }
}
