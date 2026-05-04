package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static source-level contract test that locks the Trakt 80% completion-threshold guard
 * inside [emitCompletionScrobbleStop].
 *
 * Trakt marks an item watched only if the scrobble-stop progress is ≥ 80%. The guard in
 * [PlayerRuntimeControllerPlaybackEvents.kt] enforces this by returning early when the
 * supplied [progressPercent] is below that threshold. These tests prevent accidental
 * removal of the guard during future refactors and confirm that no bypass path exists
 * (Seren player.py:441 pattern).
 */
class PlayerScrobbleCompletionClampTest {

    private val projectRoot: String
        get() = System.getProperty("user.dir")
            ?: error("Cannot determine project root")

    private fun sourceOf(relativePath: String): String {
        val file = File(projectRoot, relativePath)
        assertTrue("Source file must exist: $relativePath", file.exists())
        return file.readText()
    }

    private val playbackEvents: String by lazy {
        sourceOf(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Guard existence
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitCompletionScrobbleStop function is declared as an extension on PlayerRuntimeController`() {
        assertTrue(
            "PlayerRuntimeControllerPlaybackEvents.kt must declare " +
                "fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float)",
            playbackEvents.contains(
                "fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float)"
            )
        )
    }

    @Test
    fun `completion scrobble stop early-returns when progress is below 80 percent`() {
        assertTrue(
            "emitCompletionScrobbleStop must contain the early-return guard " +
                "'if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return'",
            playbackEvents.contains(
                "if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return"
            )
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Guard position — the guard must precede the emitScrobbleStop call inside
    // the same function body. Extract the function body and verify ordering.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `80-percent guard appears before emitScrobbleStop inside emitCompletionScrobbleStop`() {
        val fnStart = playbackEvents.indexOf(
            "fun PlayerRuntimeController.emitCompletionScrobbleStop"
        )
        assertTrue(
            "emitCompletionScrobbleStop function must exist in the source",
            fnStart >= 0
        )

        // Find the next top-level function declaration after our function's opening brace
        // to bound the search within the function body.
        val nextFnStart = playbackEvents.indexOf(
            "\ninternal fun PlayerRuntimeController.",
            fnStart + 1
        ).let { if (it < 0) playbackEvents.length else it }

        val fnBody = playbackEvents.substring(fnStart, nextFnStart)

        val guardOffset = fnBody.indexOf(
            "if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return"
        )
        val emitOffset = fnBody.indexOf("emitScrobbleStop(")

        assertTrue(
            "80%-threshold guard must be present inside emitCompletionScrobbleStop",
            guardOffset >= 0
        )
        assertTrue(
            "emitScrobbleStop call must be present inside emitCompletionScrobbleStop",
            emitOffset >= 0
        )
        assertTrue(
            "80%-threshold guard must appear before the emitScrobbleStop call " +
                "(guard at $guardOffset, emitScrobbleStop at $emitOffset)",
            guardOffset < emitOffset
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // No bypass path — emitStopScrobbleForCurrentProgress must NOT use the same
    // guard, confirming it is a distinct (non-completion) stop path that is
    // intentionally allowed to bypass the 80% threshold.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitStopScrobbleForCurrentProgress does not duplicate the completion guard`() {
        val fnStart = playbackEvents.indexOf(
            "fun PlayerRuntimeController.emitStopScrobbleForCurrentProgress"
        )
        assertTrue(
            "emitStopScrobbleForCurrentProgress must be declared in PlayerRuntimeControllerPlaybackEvents.kt",
            fnStart >= 0
        )

        val nextFnStart = playbackEvents.indexOf(
            "\ninternal fun PlayerRuntimeController.",
            fnStart + 1
        ).let { if (it < 0) playbackEvents.length else it }

        val fnBody = playbackEvents.substring(fnStart, nextFnStart)

        assertTrue(
            "emitStopScrobbleForCurrentProgress must NOT contain the 80%-threshold " +
                "completion guard — it is a separate stop path, intentionally unguarded",
            !fnBody.contains("progressPercent < 80f")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // hasSentCompletionScrobbleForCurrentItem is set to true before the coroutine
    // launches, making the guard idempotent (prevents double-fire).
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `hasSentCompletionScrobbleForCurrentItem is set to true inside emitCompletionScrobbleStop before scrobble is dispatched`() {
        val fnStart = playbackEvents.indexOf(
            "fun PlayerRuntimeController.emitCompletionScrobbleStop"
        )
        val nextFnStart = playbackEvents.indexOf(
            "\ninternal fun PlayerRuntimeController.",
            fnStart + 1
        ).let { if (it < 0) playbackEvents.length else it }

        val fnBody = playbackEvents.substring(fnStart, nextFnStart)

        val flagSetOffset = fnBody.indexOf("hasSentCompletionScrobbleForCurrentItem = true")
        val emitOffset = fnBody.indexOf("emitScrobbleStop(")

        assertTrue(
            "hasSentCompletionScrobbleForCurrentItem must be set to true inside emitCompletionScrobbleStop",
            flagSetOffset >= 0
        )
        assertTrue(
            "hasSentCompletionScrobbleForCurrentItem = true must appear before emitScrobbleStop " +
                "to prevent double-fire (flag at $flagSetOffset, emit at $emitOffset)",
            flagSetOffset < emitOffset
        )
    }
}
