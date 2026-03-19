package com.nexio.tv.debug.passthrough

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntime
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntimeBurst
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntimeRouteSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Singleton
class TransportValidationSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manifestLoader: TransportValidationManifestLoader,
    private val settingsStore: TransportValidationSettingsStore,
) {
    @Volatile
    private var currentSession: TransportValidationSessionSnapshot? = null

    fun startSession(sampleId: String): TransportValidationSessionSnapshot? {
        val manifest = runCatching { manifestLoader.loadFromAssets(context.assets) }.getOrNull() ?: return null
        val sample = manifest.samples.firstOrNull { it.id == sampleId } ?: return null
        val referenceBytes = runCatching {
            context.assets.open(sample.referenceAssetPath).use { it.readBytes() }
        }.getOrNull() ?: return null
        val session = TransportValidationSessionFactory.createSession(
            manifest = manifest,
            sampleId = sample.id,
            referenceBytes = referenceBytes
        )
        val settings = runBlocking { settingsStore.transportValidationSettings.first() }
        TransportValidationRuntime.beginSession(
            sample.id,
            sample.codecFamily.name,
            runtimeBurstLimit(settings, session.referenceBursts.size)
        )
        currentSession = session
        return session
    }

    fun snapshot(): TransportValidationSessionSnapshot? {
        val session = currentSession ?: return null
        val settings = runBlocking { settingsStore.transportValidationSettings.first() }
        val runtime = TransportValidationRuntime.snapshot()
        if (runtime == null || runtime.sampleId != session.sample.id) {
            return session
        }
        val packerInputBursts =
            runtime.packerInputBursts.mapNotNull { mapRuntimeBurst(session.sample, it) }
        val packedBursts =
            runtime.packedBursts.mapNotNull { mapRuntimeBurst(session.sample, it) }
        val audioTrackWriteBursts =
            runtime.audioTrackWriteBursts.mapNotNull { mapRuntimeBurst(session.sample, it) }
        val comparisonResults = mutableListOf<TransportValidationComparisonResult>()
        var referenceFailureBurstIndex: Int? = null
        val packedCount = minOf(session.referenceBursts.size, packedBursts.size)
        for (index in 0 until packedCount) {
            val result =
                TransportValidationComparator.compareReferenceBurst(
                    sample = session.sample,
                    reference = session.referenceBursts[index],
                    live = packedBursts[index],
                    comparisonMode = settings.comparisonMode,
                )
            comparisonResults += result
            if (settings.captureMode == TransportValidationCaptureMode.UNTIL_FAILURE && !result.passed) {
                referenceFailureBurstIndex = index
                break
            }
        }
        var audioTrackFailureBurstIndex: Int? = null
        val audioTrackCount = minOf(packedBursts.size, audioTrackWriteBursts.size)
        for (index in 0 until audioTrackCount) {
            if (referenceFailureBurstIndex != null && index > referenceFailureBurstIndex) {
                break
            }
            val result =
                TransportValidationComparator.comparePackedToAudioTrack(
                    packed = packedBursts[index],
                    audioTrack = audioTrackWriteBursts[index],
                    comparisonMode = settings.comparisonMode,
                )
            comparisonResults += result
            if (settings.captureMode == TransportValidationCaptureMode.UNTIL_FAILURE && !result.passed) {
                audioTrackFailureBurstIndex = index
                break
            }
        }
        val finalPackerInputBursts =
            trimToFailureIfNeeded(
                settings = settings,
                bursts = packerInputBursts,
                failureBurstIndex = referenceFailureBurstIndex
            )
        val finalPackedBursts =
            trimToFailureIfNeeded(
                settings = settings,
                bursts = packedBursts,
                failureBurstIndex = maxIndex(referenceFailureBurstIndex, audioTrackFailureBurstIndex)
            )
        val finalAudioTrackWriteBursts =
            trimToFailureIfNeeded(
                settings = settings,
                bursts = audioTrackWriteBursts,
                failureBurstIndex = audioTrackFailureBurstIndex
            )
        return session.copy(
            routeSnapshot = runtime.routeSnapshot?.toAppSnapshot(),
            packerInputBursts = finalPackerInputBursts,
            packedBursts = finalPackedBursts,
            audioTrackWriteBursts = finalAudioTrackWriteBursts,
            comparisonResults = comparisonResults,
        )
    }

    fun clearSession() {
        currentSession = null
        TransportValidationRuntime.clearSession()
    }

    fun exportCurrentSession(): File? {
        val session = snapshot() ?: return null
        val settings = runBlocking { settingsStore.transportValidationSettings.first() }
        val outputFile = TransportValidationDiagnosticsExporter.exportBundle(
            session = session,
            outputDirectory = File(context.filesDir, EXPORT_DIRECTORY_NAME),
            includeBinaryDumps = settings.binaryDumpsEnabled,
        )
        Log.i(TAG, "Exported passthrough transport validation bundle to ${outputFile.absolutePath}")
        return outputFile
    }

    companion object {
        private const val TAG = "TransportValidation"
        private const val EXPORT_DIRECTORY_NAME = "transport-validation"
        private const val IEC_PREAMBLE_BYTES = 8
    }

    private fun mapRuntimeBurst(
        sample: TransportValidationSample,
        burst: TransportValidationRuntimeBurst
    ): TransportValidationBurstRecord? {
        val bytes = burst.bytes
        return if (bytes.size >= IEC_PREAMBLE_BYTES) {
            TransportValidationReferenceParser.parseBurst(
                sample = sample,
                bytes = bytes,
                burstIndex = burst.burstIndex,
                sourcePtsUs = burst.sourcePtsUs,
            )
        } else {
            TransportValidationBurstRecord(
                codecFamily = sample.codecFamily,
                sampleId = sample.id,
                burstIndex = burst.burstIndex,
                sourcePtsUs = burst.sourcePtsUs,
                rawBytes = bytes.copyOf(),
                burstSizeBytes = bytes.size,
                pa = 0,
                pb = 0,
                rawPc = 0,
                pd = 0,
                payloadBytes = bytes.size,
                zeroPaddingBytes = 0,
                first64ByteHash = sha256Hex(bytes.copyOfRange(0, minOf(64, bytes.size))),
                fullBurstHash = sha256Hex(bytes),
                codecFailureHint = TransportValidationFailureCode.BURST_ALIGNMENT_FAILED,
            )
        }
    }

    private fun TransportValidationRuntimeRouteSnapshot.toAppSnapshot(): TransportValidationRouteSnapshot =
        TransportValidationRouteSnapshot(
            deviceName = deviceName,
            encoding = encoding,
            sampleRate = sampleRate.takeIf { it > 0 },
            channelMask = channelMask,
            directPlaybackSupported = directPlaybackSupported,
            audioTrackState = audioTrackState,
        )

    private fun runtimeBurstLimit(
        settings: TransportValidationSettings,
        referenceBurstCount: Int,
    ): Int =
        when (settings.captureMode) {
            TransportValidationCaptureMode.PREAMBLE_ONLY,
            TransportValidationCaptureMode.FIRST_N_BURSTS -> maxOf(1, settings.captureBurstCount)
            TransportValidationCaptureMode.UNTIL_FAILURE -> maxOf(referenceBurstCount, 64)
        }

    private fun trimToFailureIfNeeded(
        settings: TransportValidationSettings,
        bursts: List<TransportValidationBurstRecord>,
        failureBurstIndex: Int?,
    ): List<TransportValidationBurstRecord> {
        if (settings.captureMode != TransportValidationCaptureMode.UNTIL_FAILURE ||
            failureBurstIndex == null
        ) {
            return bursts
        }
        return bursts.filter { it.burstIndex <= failureBurstIndex }
    }

    private fun maxIndex(first: Int?, second: Int?): Int? =
        when {
            first == null -> second
            second == null -> first
            else -> maxOf(first, second)
        }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }
}
