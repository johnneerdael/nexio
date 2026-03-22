package com.nexio.tv.debug.passthrough

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KodiTrueHdNativeAudioSinkSourceStructureTest {

    @Test
    fun handleBufferPassivelySyncsTrueHdStartupOwnershipFromNative() {
        val source = loadSource()

        assertFalse(source.contains("maybeExitTrueHdStartupOwnership(\"handleBuffer\")"))
        assertTrue(source.contains("syncTrueHdStartupStateFromNative(\"handleBuffer\")"))
        assertEquals(
            1,
            Regex("""syncTrueHdStartupStateFromNative\(\"""").findAll(source).count(),
        )
    }

    @Test
    fun startupHandlerDoesNotBecomeDirectWritePath() {
        val startupMethod =
            extractMethod(
                loadSource(),
                "private boolean handleTrueHdStartupBuffer(",
            )

        assertFalse(startupMethod.contains("return writeBufferDirect("))
        assertFalse(startupMethod.contains("handleTrueHdSteadyStateBuffer("))
    }

    @Test
    fun trueHdDiagnosticsObserveNativeHandoffReadyTruth() {
        val source = loadSource()

        assertTrue(source.contains("nIsTrueHdSteadyStateHandoffReady(nativeHandle)"))
        assertTrue(source.contains("\"nativeHandoffReady\""))
    }

    @Test
    fun handleBufferBecomesObservationalAfterTrueHdStartupCompletes() {
        val source = loadSource()
        val handleBufferMethod =
            extractMethod(
                source,
                "public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)",
            )

        assertTrue(
            handleBufferMethod.contains(
                "if (trueHdStartupCompleted) {\n"
                    + "        recordTrueHdPathDecision(\"steady_state_path\", false, false);\n"
                    + "        return handleTrueHdSteadyStateBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);\n"
                    + "      }"
            )
        )
        assertTrue(
            handleBufferMethod.contains(
                "refreshTrueHdNativeHandoffReady();\n"
                    + "      boolean handoffTriggered = syncTrueHdStartupStateFromNative(\"handleBuffer\");"
            )
        )
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
                "java",
                "androidx",
                "media3",
                "exoplayer",
                "audio",
                "kodi",
                "KodiTrueHdNativeAudioSink.java",
            )
        val directCandidate = cwd.resolve(relativePath)
        val moduleCandidate = cwd.resolve("..").resolve(relativePath).normalize()
        val sourcePath =
            when {
                Files.exists(directCandidate) -> directCandidate
                Files.exists(moduleCandidate) -> moduleCandidate
                else ->
                    error(
                        "Unable to locate KodiTrueHdNativeAudioSink.java from working directory $cwd",
                    )
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
