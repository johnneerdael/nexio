package com.nexio.tv.ui.screens.player

import java.io.File
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerSeekRecoveryContractTest {

    @Test
    fun `internal player keeps exact seek semantics and disables frame-rate switching like Nuvio`() {
        val source = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
        ).readText()

        assertThat(
            source.contains("import androidx.media3.exoplayer.SeekParameters")
        ).isFalse()
        assertThat(
            source.contains(".setSeekParameters(SeekParameters.CLOSEST_SYNC)")
        ).isFalse()
        assertThat(
            source.contains(".setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)")
        ).isTrue()
        assertThat(
            source.contains(".setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)")
        ).isTrue()
    }

    @Test
    fun `plain progressive http transport matches Nuvio default data source shape`() {
        val source = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerMediaSourceFactory.kt"
        ).readText()
        val fallbackBranch = source.substringAfter("else -> {")
            .substringBefore("}\n        }\n    }\n\n    private fun createDiskSpoolFactoryIfEligible")

        assertThat(fallbackBranch.contains("currentWarmAheadUpstreamFactory = null")).isTrue()
        assertThat(fallbackBranch.contains("baseDataSourceFactory")).isTrue()
        assertThat(fallbackBranch.contains("okHttpFactory")).isFalse()
    }

    @Test
    fun `mid-stream buffering retry preserves current playback position`() {
        val source = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt"
        ).readText()
        val retryStart = source.indexOf("PlayerEvent.OnRetry ->")
        assertThat(retryStart).isAtLeast(0)
        val retryBody = source.substring(retryStart)
            .substringBefore("PlayerEvent.OnLoadingTimedOut ->")

        assertThat(
            retryBody.contains("currentPosition")
        ).isTrue()
        assertThat(
            retryBody.contains("scheduleDeferredPlayerReinitialize(fromPositionMs = currentPosition)")
        ).isTrue()
    }

    @Test
    fun `post-seek watchdogs only log diagnostics without resetting playback`() {
        val controllerSource = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt"
        ).readText()
        val eventsSource = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt"
        ).readText()
        val observersSource = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt"
        ).readText()
        val initializationSource = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
        ).readText()

        assertThat(controllerSource.contains("SEEK_FIRST_FRAME_TIMEOUT_MS")).isTrue()
        assertThat(controllerSource.contains("SEEK_PROGRESS_TIMEOUT_MS")).isTrue()
        assertThat(controllerSource.contains("seekFirstFrameWatchdogJob")).isTrue()
        assertThat(controllerSource.contains("seekProgressWatchdogJob")).isTrue()
        assertThat(eventsSource.contains("maybeScheduleSeekFirstFrameWatchdog(requestTimeMs, targetMs)"))
            .isTrue()
        assertThat(eventsSource.contains("maybeScheduleSeekProgressWatchdog(requestTimeMs, targetMs)"))
            .isTrue()
        assertThat(observersSource.contains("SEEK_FIRST_FRAME_TIMEOUT")).isTrue()
        assertThat(observersSource.contains("SEEK_PROGRESS_TIMEOUT")).isTrue()
        assertThat(observersSource.contains("bufferedPositionMs")).isTrue()
        assertThat(observersSource.contains("totalBufferedDurationMs")).isTrue()
        assertThat(
            observersSource.contains("scheduleDeferredPlayerReinitialize(fromPositionMs = recoveryPosition)")
        ).isFalse()
        assertThat(
            observersSource.contains("scheduleDeferredPlayerReinitialize(fromPositionMs = maxOf(targetMs, currentPosition))")
        ).isFalse()
        assertThat(initializationSource.contains("cancelSeekFirstFrameWatchdog()")).isTrue()
        assertThat(initializationSource.contains("cancelSeekProgressWatchdog()")).isTrue()
    }

    @Test
    fun `seek resume path reasserts autoplay after ready and first frame like Nuvio`() {
        val source = File(
            "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt"
        ).readText()

        val readyBlock = source.substringAfter("if (shouldEnforceAutoplayOnFirstReady)")
            .substringBefore("tryApplyPendingResumeProgress(this@apply)")
        assertThat(
            readyBlock.contains("} else if (!userPausedManually && hasRenderedFirstFrame) {")
        ).isTrue()
        assertThat(readyBlock.contains("playWhenReady = true")).isTrue()
        assertThat(readyBlock.contains("play()")).isTrue()

        val firstFrameBlock = source.substringAfter("mediaSourceFactory.notifyPlaybackFirstFrameRendered()")
            .substringBefore("maybeSchedulePostFirstFrameBufferingWatchdog(")
        assertThat(firstFrameBlock.contains("hasRenderedFirstFrame = true")).isTrue()
        assertThat(firstFrameBlock.contains("if (!userPausedManually)")).isTrue()
        assertThat(firstFrameBlock.contains("playWhenReady = true")).isTrue()
        assertThat(firstFrameBlock.contains("play()")).isTrue()
    }
}
