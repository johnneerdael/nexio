package com.nexio.tv.debug.passthrough

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KodiTrueHdAEEngineSourceStructureTest {

    @Test
    fun steadyStateRetryPolicyDoesNotUseFixedTwentyMillisecondLockout() {
        val source = loadSource()

        assertFalse(source.contains("kSteadyStateRetryZeroBackoffUs = 20000"))
    }

    @Test
    fun flushLoopDoesNotDuplicateSteadyStateBackoffGate() {
        val flushMethod =
            extractMethod(
                loadSource(),
                "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
            )

        assertFalse(flushMethod.contains("steadyStateRetryBackoffActive"))
        assertFalse(flushMethod.contains("!steadyStateRetryingPendingRemainder && !shouldRetry"))
        assertFalse(flushMethod.contains("if (!shouldRetry)"))
    }

    @Test
    fun steadyStateRetryAdmissionDoesNotDependOnAudioTrackPlayState() {
        val flushMethod =
            extractMethod(
                loadSource(),
                "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
            )

        assertFalse(flushMethod.contains("retryingPendingRemainder && output_.IsPlaying()"))
    }

    @Test
    fun steadyStateRetryDiagnosticsDoNotFallBackToForcedRetry() {
        val flushMethod =
            extractMethod(
                loadSource(),
                "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
            )

        assertFalse(flushMethod.contains("\"forced_retry\""))
    }

    @Test
    fun steadyStateRetryDiagnosticsDoNotUseUnsetFallbackReason() {
        val flushMethod =
            extractMethod(
                loadSource(),
                "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
            )

        assertFalse(flushMethod.contains("\"steady_state_retry_reason_unset\""))
    }

    @Test
    fun steadyStateRetryProgressDoesNotResetRetryEpisodeBeforePacketCompletion() {
        val flushMethod =
            extractMethod(
                loadSource(),
                "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
            )

        assertFalse(
            flushMethod.contains(
                "if (retryingPendingRemainder && isSteadyState)\n      activeRetryState->Reset();",
            ),
        )
    }

    @Test
    fun steadyStateRetryCadenceDoesNotUseFixedFourMillisecondBackoff() {
        val engineSource = loadSource()

        assertFalse(engineSource.contains("kSteadyStateRepeatedZeroBackoffUs = 4000"))
    }

    @Test
    fun pendingPackedRetryStateTracksNextEligibleRetryTime() {
        val headerSource = loadHeaderSource()
        val startupRetryState = extractStruct(headerSource, "struct PendingPackedRetryState")

        assertTrue(startupRetryState.contains("nextEligibleRetryTimeUs_"))
    }

    @Test
    fun steadyStateControlStateDoesNotTrackNextEligibleRetryTime() {
        val headerSource = loadHeaderSource()
        val steadyStateControlState =
            extractStruct(headerSource, "struct PendingSteadyStateControlState")

        assertFalse(steadyStateControlState.contains("nextEligibleRetryTimeUs_"))
    }

    @Test
    fun steadyStateRetryBackoffHelperIsRemoved() {
        val headerSource = loadHeaderSource()
        val engineSource = loadSource()

        assertFalse(headerSource.contains("ComputeSteadyStateRetryBackoffUsLocked("))
        assertFalse(engineSource.contains("KodiTrueHdAEEngine::ComputeSteadyStateRetryBackoffUsLocked("))
    }

    @Test
    fun trueHdEngineKeepsStartupRetryStateIsolated() {
        val headerSource = loadHeaderSource()

        assertTrue(headerSource.contains("PendingPackedRetryState startupRetryState_"))
    }

    @Test
    fun trueHdEngineExposesNativeSteadyStateHandoffReadyQuery() {
        val headerSource = loadHeaderSource()

        assertTrue(headerSource.contains("bool IsTrueHdSteadyStateHandoffReady()"))
    }

    @Test
    fun trueHdEngineTracksSplitStartupAndSteadyStatePendingInput() {
        val headerSource = loadHeaderSource()

        assertTrue(headerSource.contains("startupPendingPassthroughInput_"))
        assertTrue(headerSource.contains("steadyStatePendingPassthroughInput_"))
        assertFalse(headerSource.contains("std::optional<PendingPassthroughInput> pendingPassthroughInput_"))
    }

    @Test
    fun trueHdEngineExposesSplitStartupAndSteadyStatePendingPackedOutputSlots() {
        val headerSource = loadHeaderSource()

        assertTrue(headerSource.contains("startupPendingPackedOutput_"))
        assertTrue(headerSource.contains("steadyStatePendingPackedOutput_"))
    }

    @Test
    fun trueHdEngineExposesPackedOutputOwnershipBoundary() {
        val headerSource = loadHeaderSource()

        assertTrue(headerSource.contains("PendingPassthroughOwner GetActiveTrueHdPendingPackedOutputOwnerLocked()"))
        assertFalse(
            headerSource.contains(
                "std::optional<KodiPackedAccessUnit>& GetPendingPackedOutputSlotLocked(",
            ),
        )
        assertFalse(
            headerSource.contains(
                "PendingPackedRetryState& GetPendingPackedRetryStateLocked(",
            ),
        )
    }

    @Test
    fun trueHdEngineOwnsSteadyStatePacketAndDedicatedControlMetadataInSingleTruthObject() {
        val headerSource = loadHeaderSource()

        assertTrue(headerSource.contains("struct PendingSteadyStatePackedOutput"))
        assertTrue(headerSource.contains("struct PendingSteadyStateControlState"))
        assertTrue(headerSource.contains("KodiPackedAccessUnit packet;"))
        assertTrue(headerSource.contains("PendingSteadyStateControlState controlState;"))
        assertTrue(
            headerSource.contains(
                "std::optional<PendingSteadyStatePackedOutput> steadyStatePendingPackedOutput_",
            ),
        )
        assertFalse(headerSource.contains("PendingPackedRetryState retryState;"))
        assertFalse(headerSource.contains("PendingPackedRetryState& GetPendingPackedRetryStateLocked("))
        assertFalse(headerSource.contains("PendingPackedRetryState steadyStateRetryState_"))
    }

    @Test
    fun steadyStateZeroWritesUsePacketDurationBackoffReason() {
        val flushMethod =
            extractMethod(
                loadSource(),
                "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
            )

        assertTrue(flushMethod.contains("\"steady_state_packet_duration_backoff\""))
    }

    @Test
    fun steadyStateDrainDoesNotGatePendingTruthThroughRetryEligibility() {
        val flushMethod =
            extractMethod(
                loadSource(),
                "int KodiTrueHdAEEngine::FlushTrueHdPackedQueueToHardwareLocked()",
            )

        assertFalse(flushMethod.contains("ShouldRetrySteadyStatePendingPackedRemainderLocked("))
        assertFalse(flushMethod.contains("if (!shouldRetry)"))
    }

    private fun loadSource(): String {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val relativePath =
            Paths.get(
                "media",
                "libraries",
                "exoplayer_kodi_cpp_audiosink",
                "src",
                "main",
                "jni",
                "src",
                "KodiTrueHdAEEngine.cpp",
            )
        val directCandidate = cwd.resolve(relativePath)
        val moduleCandidate = cwd.resolve("..").resolve(relativePath).normalize()
        val sourcePath =
            when {
                Files.exists(directCandidate) -> directCandidate
                Files.exists(moduleCandidate) -> moduleCandidate
                else -> error("Unable to locate KodiTrueHdAEEngine.cpp from working directory $cwd")
            }
        return String(Files.readAllBytes(sourcePath), Charsets.UTF_8)
    }

    private fun loadHeaderSource(): String {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val relativePath =
            Paths.get(
                "media",
                "libraries",
                "exoplayer_kodi_cpp_audiosink",
                "src",
                "main",
                "jni",
                "src",
                "KodiTrueHdAEEngine.h",
            )
        val directCandidate = cwd.resolve(relativePath)
        val moduleCandidate = cwd.resolve("..").resolve(relativePath).normalize()
        val sourcePath =
            when {
                Files.exists(directCandidate) -> directCandidate
                Files.exists(moduleCandidate) -> moduleCandidate
                else -> error("Unable to locate KodiTrueHdAEEngine.h from working directory $cwd")
            }
        return String(Files.readAllBytes(sourcePath), Charsets.UTF_8)
    }

    private fun extractMethod(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        require(signatureIndex >= 0) { "Signature not found: $signature" }
        val bodyStart = source.indexOf('{', signatureIndex)
        require(bodyStart >= 0) { "No body found for signature: $signature" }

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(signatureIndex, index + 1)
                    }
                }
            }
        }

        error("Unterminated method body for signature: $signature")
    }

    private fun extractStruct(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        require(signatureIndex >= 0) { "Struct not found: $signature" }
        val bodyStart = source.indexOf('{', signatureIndex)
        require(bodyStart >= 0) { "No body found for struct: $signature" }

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(signatureIndex, index + 1)
                    }
                }
            }
        }

        error("Unterminated struct body for signature: $signature")
    }
}
