package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static source-level contract test that locks the per-item completion-scrobble dedup guard
 * inside [PlayerRuntimeControllerPlaybackEvents.kt].
 *
 * ExoPlayer can fire end-of-stream callbacks multiple times in quick succession during teardown.
 * The [hasSentCompletionScrobbleForCurrentItem] flag prevents a second `/scrobble/stop` being
 * sent for the same item. Three invariants are locked here:
 *
 *  1. [emitCompletionScrobbleStop] early-returns when the flag is already set (dedup guard).
 *  2. The flag is set to `true` BEFORE [emitScrobbleStop] is called — not after — eliminating
 *     a race where a concurrent end-of-stream fires between the dispatch and the flag-set.
 *  3. The flag is reset to `false` inside [refreshScrobbleItem] so that switching to a new item
 *     re-enables completion scrobbling.
 *
 * A fourth optional test confirms that only [emitCompletionScrobbleStop] can flip the flag to
 * `true`, preventing an unexpected code path from marking an item completed.
 */
class PlayerScrobbleCompletionGuardTest {

    private val projectRoot: String
        get() = System.getProperty("user.dir")
            ?: error("Cannot determine project root")

    private fun source(): String {
        val rel = "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt"
        val file = File(projectRoot, rel)
        assertTrue("Source file must exist: $rel", file.exists())
        return file.readText()
    }

    /**
     * Extracts the body of the first function whose declaration contains [fnName], bounded
     * by the next top-level `internal fun PlayerRuntimeController.` declaration (or EOF).
     * Mirrors the boundary-detection strategy used in [PlayerScrobbleThresholdSplitContractTest].
     */
    private fun bodyOf(fnName: String): String {
        val src = source()
        val fnStart = src.indexOf(fnName)
        assertTrue("Function containing '$fnName' must exist in source", fnStart >= 0)
        val nextFnStart = src.indexOf("\ninternal fun PlayerRuntimeController.", fnStart + 1)
            .let { if (it < 0) src.length else it }
        return src.substring(fnStart, nextFnStart)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Dedup guard: emitCompletionScrobbleStop checks the flag before firing
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitCompletionScrobbleStop guards on hasSentCompletionScrobbleForCurrentItem`() {
        val body = bodyOf("fun PlayerRuntimeController.emitCompletionScrobbleStop")

        assertTrue(
            "emitCompletionScrobbleStop must reference 'hasSentCompletionScrobbleForCurrentItem' " +
                "in its early-return guard to prevent duplicate completion scrobbles when ExoPlayer " +
                "fires end-of-stream multiple times",
            body.contains("hasSentCompletionScrobbleForCurrentItem")
        )
        // Confirm it appears on a `return` line, i.e. it is part of the guard and not only in an assignment.
        val returnLine = body.lines().firstOrNull { it.contains("return") && it.contains("hasSentCompletionScrobbleForCurrentItem") }
        assertTrue(
            "hasSentCompletionScrobbleForCurrentItem must appear on the early-return line inside " +
                "emitCompletionScrobbleStop, not only in an assignment",
            returnLine != null
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Flag-set ordering: assignment must come BEFORE the dispatch call
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitCompletionScrobbleStop sets hasSentCompletionScrobbleForCurrentItem to true before dispatching`() {
        val body = bodyOf("fun PlayerRuntimeController.emitCompletionScrobbleStop")

        val assignmentIdx = body.indexOf("hasSentCompletionScrobbleForCurrentItem = true")
        val dispatchIdx   = body.indexOf("emitScrobbleStop(")

        assertTrue(
            "emitCompletionScrobbleStop must assign hasSentCompletionScrobbleForCurrentItem = true",
            assignmentIdx >= 0
        )
        assertTrue(
            "emitCompletionScrobbleStop must call emitScrobbleStop()",
            dispatchIdx >= 0
        )
        assertTrue(
            "hasSentCompletionScrobbleForCurrentItem = true must appear BEFORE emitScrobbleStop() " +
                "to prevent a race where a concurrent end-of-stream callback fires between the dispatch " +
                "and the flag-set, causing a duplicate completion scrobble",
            assignmentIdx < dispatchIdx
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. Reset: refreshScrobbleItem must clear the flag for the next item
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `hasSentCompletionScrobbleForCurrentItem resets when a new item starts`() {
        val body = bodyOf("fun PlayerRuntimeController.refreshScrobbleItem")

        assertTrue(
            "refreshScrobbleItem must set hasSentCompletionScrobbleForCurrentItem = false so that " +
                "switching to a new video re-enables completion scrobbling for that item",
            body.contains("hasSentCompletionScrobbleForCurrentItem = false")
        )
        // The reset must be paired with a generation bump — confirm scrobbleStartRequestGeneration++
        // also appears in the same function body (both resets belong together).
        assertTrue(
            "refreshScrobbleItem must also increment scrobbleStartRequestGeneration++, confirming " +
                "the full item-start reset sequence is present",
            body.contains("scrobbleStartRequestGeneration++")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. (Optional) Only emitCompletionScrobbleStop may set the flag to true
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitCompletionScrobbleStop is the only function that sets hasSentCompletionScrobbleForCurrentItem to true`() {
        val src = source()

        // Count all occurrences of the assignment to `true` in the whole file.
        val trueAssignmentCount = src.split("hasSentCompletionScrobbleForCurrentItem = true").size - 1

        assertTrue(
            "hasSentCompletionScrobbleForCurrentItem = true must appear exactly once in the file " +
                "(inside emitCompletionScrobbleStop). Found $trueAssignmentCount occurrence(s). " +
                "An unexpected code path setting the flag to true could mark an item completed " +
                "without going through the 80% threshold guard.",
            trueAssignmentCount == 1
        )

        // Confirm that single occurrence is inside emitCompletionScrobbleStop.
        val body = bodyOf("fun PlayerRuntimeController.emitCompletionScrobbleStop")
        assertTrue(
            "The sole 'hasSentCompletionScrobbleForCurrentItem = true' assignment must reside " +
                "inside emitCompletionScrobbleStop",
            body.contains("hasSentCompletionScrobbleForCurrentItem = true")
        )
    }
}
