package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static source-level contract test that locks the Trakt ≥80% completion vs <80% pause
 * split inside [PlayerRuntimeControllerPlaybackEvents.kt].
 *
 * Trakt semantics:
 *  - `/scrobble/stop` with progress ≥ 80% → item marked **watched**
 *  - `/scrobble/pause` with progress < 80% → recorded as in-progress pause
 *
 * The split is enforced by two distinct extension functions:
 *  - [emitCompletionScrobbleStop] — early-returns when progress < 80f, preventing a
 *    watched-mark at low progress.
 *  - [emitPauseScrobble] — accepts any progress ≥ 1f with no 80% gate, acting as the
 *    below-80 pause path.
 *
 * These tests prevent a future refactor from silently collapsing the split (e.g. marking a
 * 50%-progress stop as "watched", or failing to mark a 95%-progress stop at all).
 */
class PlayerScrobbleThresholdSplitContractTest {

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
     * Mirrors the boundary-detection strategy used in [PlayerScrobbleCompletionClampTest].
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
    // 1. 80% split contract: emitCompletionScrobbleStop contains the guard
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitStopScrobbleForCurrentProgress split contract - emitCompletionScrobbleStop contains the 80-percent guard`() {
        val body = bodyOf("fun PlayerRuntimeController.emitCompletionScrobbleStop")

        assertTrue(
            "emitCompletionScrobbleStop must contain the '< 80f' threshold guard that prevents " +
                "marking an item watched when progress is below 80%",
            body.contains("< 80f")
        )
        assertTrue(
            "emitCompletionScrobbleStop must delegate to emitScrobbleStop to dispatch the " +
                "completion-stop scrobble event",
            body.contains("emitScrobbleStop(")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Route ordering: pause branch has no 80% gate; completion branch does
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitStopScrobbleForCurrentProgress split contract - pause path has no 80-percent gate and completion path does`() {
        val pauseBody = bodyOf("fun PlayerRuntimeController.emitPauseScrobble")
        val completionBody = bodyOf("fun PlayerRuntimeController.emitCompletionScrobbleStop")

        assertTrue(
            "emitPauseScrobble is the below-80 path and must NOT contain a '< 80f' guard — " +
                "it should accept any progress ≥ 1f",
            !pauseBody.contains("< 80f")
        )
        assertTrue(
            "emitCompletionScrobbleStop is the above-80 path and MUST contain a '< 80f' guard",
            completionBody.contains("< 80f")
        )
        assertTrue(
            "emitPauseScrobble must dispatch the provider pause endpoint",
            pauseBody.contains("trackingScrobbleService.scrobblePause(")
        )
        assertTrue(
            "emitCompletionScrobbleStop must delegate to emitScrobbleStop (the shared dispatch path)",
            completionBody.contains("emitScrobbleStop(")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. emitPauseScrobble is the only path below-80 — it calls provider pause,
    //    not provider stop.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitStopScrobbleForCurrentProgress split contract - emitPauseScrobble calls tracking pause directly`() {
        val body = bodyOf("fun PlayerRuntimeController.emitPauseScrobble")

        assertTrue(
            "emitPauseScrobble must NOT call trackingScrobbleService.scrobbleStop directly — " +
                "pause must not mark watched through provider stop",
            !body.contains("trackingScrobbleService.scrobbleStop")
        )
        assertTrue(
            "emitPauseScrobble must call trackingScrobbleService.scrobblePause",
            body.contains("trackingScrobbleService.scrobblePause(")
        )
        assertTrue(
            "emitPauseScrobble must not delegate to emitScrobbleStop",
            !body.contains("emitScrobbleStop(")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. emitCompletionScrobbleStop is the only path with >= 80f gating that
    //    delegates to trackingScrobbleService.scrobbleStop indirectly — confirm
    //    it does NOT call trackingScrobbleService.scrobbleStop directly either,
    //    proving the shared emitScrobbleStop dispatch is the sole call site
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitStopScrobbleForCurrentProgress split contract - emitCompletionScrobbleStop does not call trackingScrobbleService directly`() {
        val body = bodyOf("fun PlayerRuntimeController.emitCompletionScrobbleStop")

        assertTrue(
            "emitCompletionScrobbleStop must NOT call trackingScrobbleService.scrobbleStop " +
                "directly — it must go through emitScrobbleStop, which owns the ≥80f allowWithoutStart " +
                "logic and coroutine dispatch",
            !body.contains("trackingScrobbleService.scrobbleStop")
        )
        assertTrue(
            "emitCompletionScrobbleStop must delegate to emitScrobbleStop",
            body.contains("emitScrobbleStop(")
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Guard position — the 80% gate must precede the emitScrobbleStop call
    //    inside emitCompletionScrobbleStop
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `emitStopScrobbleForCurrentProgress split contract - 80-percent guard precedes emitScrobbleStop inside emitCompletionScrobbleStop`() {
        val body = bodyOf("fun PlayerRuntimeController.emitCompletionScrobbleStop")

        val guardOffset = body.indexOf("< 80f")
        val emitOffset = body.indexOf("emitScrobbleStop(")

        assertTrue(
            "80% guard ('< 80f') must be present inside emitCompletionScrobbleStop",
            guardOffset >= 0
        )
        assertTrue(
            "emitScrobbleStop call must be present inside emitCompletionScrobbleStop",
            emitOffset >= 0
        )
        assertTrue(
            "80% guard must appear BEFORE the emitScrobbleStop call inside " +
                "emitCompletionScrobbleStop (guard at $guardOffset, emitScrobbleStop at $emitOffset) — " +
                "a guard that follows the dispatch would allow the below-80 case through",
            guardOffset < emitOffset
        )
    }
}
