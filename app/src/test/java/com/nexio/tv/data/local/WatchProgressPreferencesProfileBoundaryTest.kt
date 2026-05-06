package com.nexio.tv.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WatchProgressPreferencesProfileBoundaryTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("watch_progress_profile_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile }
        )
    }

    @Test
    fun `content progress query reads the requested profile store`() = runTest {
        val preferences = WatchProgressPreferences(factory)
        val contentId = uniqueContentId("tt-shared")
        val profileOneFlow = preferences.getProgress(profileId = 1, contentId = contentId)
        val profileTwoFlow = preferences.getProgress(profileId = 2, contentId = contentId)

        preferences.saveProgress(1, sampleProgress(contentId = contentId, name = "profile one"))
        assertEquals("profile one", profileOneFlow.first()?.name)
        assertEquals(null, profileTwoFlow.first())

        preferences.saveProgress(2, sampleProgress(contentId = contentId, name = "profile two"))

        assertEquals("profile one", profileOneFlow.first()?.name)
        assertEquals("profile two", profileTwoFlow.awaitValue { it?.name == "profile two" }?.name)
    }

    @Test
    fun `episode progress map query reads the requested profile store`() = runTest {
        val preferences = WatchProgressPreferences(factory)
        val contentId = uniqueContentId("tt-series")
        val profileOneMapFlow = preferences.getAllEpisodeProgress(profileId = 1, contentId = contentId)
        val profileOneEpisodeFlow = preferences.getEpisodeProgress(profileId = 1, contentId = contentId, season = 1, episode = 1)
        val profileTwoMapFlow = preferences.getAllEpisodeProgress(profileId = 2, contentId = contentId)
        val profileTwoEpisodeFlow = preferences.getEpisodeProgress(profileId = 2, contentId = contentId, season = 1, episode = 1)

        preferences.saveProgress(
            1,
            sampleProgress(
                contentId = contentId,
                name = "profile one episode",
                season = 1,
                episode = 1
            )
        )
        assertEquals("profile one episode", profileOneMapFlow.first()[1 to 1]?.name)
        assertEquals("profile one episode", profileOneEpisodeFlow.first()?.name)
        assertEquals(null, profileTwoEpisodeFlow.first())

        preferences.saveProgress(
            2,
            sampleProgress(
                contentId = contentId,
                name = "profile two episode",
                season = 1,
                episode = 1
            )
        )

        assertEquals("profile one episode", profileOneMapFlow.first()[1 to 1]?.name)
        val profileTwoMap = profileTwoMapFlow.awaitValue { it[1 to 1]?.name == "profile two episode" }
        val profileTwoEpisode = profileTwoEpisodeFlow.awaitValue { it?.name == "profile two episode" }
        assertEquals("profile two episode", profileTwoMap[1 to 1]?.name)
        assertEquals("profile two episode", profileTwoEpisode?.name)
    }

    private suspend fun <T> Flow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                first(predicate)
            }
        }

    private fun uniqueContentId(prefix: String): String =
        "$prefix-${System.nanoTime()}"

    private fun sampleProgress(
        contentId: String,
        name: String,
        season: Int? = null,
        episode: Int? = null
    ): WatchProgress =
        WatchProgress(
            contentId = contentId,
            contentType = if (season != null) "series" else "movie",
            name = name,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = if (season != null && episode != null) "$contentId:s${season}e$episode" else contentId,
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 100L,
            duration = 1_000L,
            progressPercent = 10f,
            lastWatched = System.currentTimeMillis()
        )
}
