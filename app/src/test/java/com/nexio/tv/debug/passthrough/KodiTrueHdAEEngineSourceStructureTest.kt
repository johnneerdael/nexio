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
        assertTrue(flushMethod.contains("if (!shouldRetry)"))
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
}
