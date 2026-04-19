package com.nexio.tv.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProgressRuntimeStateTest {

    @Test
    fun `profile states are independent`() {
        val source = source()

        assertTrue(source.contains("private class TraktProgressRuntimeRegistry"))
        assertTrue(source.contains("private val states = mutableMapOf<Int, TraktProgressRuntimeState>()"))
        assertTrue(source.contains("states.getOrPut(session.profileId)"))
        assertTrue(source.contains("private val remoteProgress get() = runtimeState().remoteProgress"))
        assertTrue(source.contains("private val myShowsNextUp get() = runtimeState().myShowsNextUp"))
    }

    @Test
    fun `clearing one profile does not clear another profile`() {
        val source = source()

        assertTrue(source.contains("fun clearProfile(profileId: Int)"))
        assertTrue(source.contains("states[profileId]?.clear()"))
        assertTrue(source.contains("fun clear()"))
        assertTrue(source.contains("hasLoadedRemoteProgress.value = false"))
    }

    @Test
    fun `continue watching playback fetches are not age windowed`() {
        val source = source()

        assertTrue(source.contains("val inProgressMovies = getPlayback(\"movies\", force = force)"))
        assertTrue(source.contains("val inProgressEpisodes = getPlayback(\"episodes\", force = force)"))
        assertTrue(!source.contains("recentWatchWindowMs"))
        assertTrue(!source.contains("continueWatchingWindowDays"))
    }

    private fun source(): String =
        File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()
}
