package com.nexio.tv.ui.screens.player

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStreamingCacheFillWiringTest {

    @Test
    fun `lazy video size discovery retries streaming cache fill start`() {
        val source = sourceFile(
            "com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt"
        ).toFile().readText()
        val sizeDiscoveryBlock = source.substringAfter("if (currentVideoSize == null)")
            .substringBefore("// Update cache now that we have the computed hash")

        assertTrue(
            "When lazy hashing discovers currentVideoSize, streaming cache fill must be retried",
            sizeDiscoveryBlock.contains("maybeStartStreamingCacheFillForCurrentStream()")
        )
    }

    @Test
    fun `cache fill playback byte provider reads atomic snapshot`() {
        val source = sourceFile(
            "com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt"
        ).toFile().readText()
        val maybeStartBlock = source.substringAfter("internal fun maybeStartStreamingCacheFillForCurrentStream()")
            .substringBefore("fun stopAndRelease()")

        assertTrue(
            "CacheFillWorker playbackByteProvider must not call ExoPlayer from CacheFill-0",
            maybeStartBlock.contains("playbackByteProvider = { streamingCachePlaybackBytePosition.get() }")
        )
    }

    private fun sourceFile(relativePath: String): Path {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val relative = Paths.get("app", "src", "main", "java")
        val directCandidate = cwd.resolve(relative)
        val parentCandidate = cwd.resolve("..").resolve(relative).normalize()
        val sourceRoot = when {
            Files.exists(directCandidate) -> directCandidate
            Files.exists(parentCandidate) -> parentCandidate
            else -> error("Unable to locate app/src/main/java from working directory $cwd")
        }
        return sourceRoot.resolve(relativePath).also { path ->
            require(path.invariantSeparatorsPathString.endsWith(relativePath))
        }
    }
}
