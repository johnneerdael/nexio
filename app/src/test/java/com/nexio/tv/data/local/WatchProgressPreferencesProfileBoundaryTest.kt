package com.nexio.tv.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nexio.tv.core.profile.ProfileManager
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

    private fun TestScope.makeManager(): ProfileManager {
        val dataStoreImpl = ProfileDataStoreImpl(createDataStore(backgroundScope), Gson())
        return ProfileManager(
            dataStore = dataStoreImpl,
            factory = factory,
            context = context,
            scope = backgroundScope
        )
    }

    @Test
    fun `content progress query follows active profile switches`() = runTest {
        val manager = makeManager()
        val preferences = WatchProgressPreferences(factory, manager)
        val contentId = uniqueContentId("tt-shared")
        val progressFlow = preferences.getProgress(contentId)

        preferences.saveProgress(sampleProgress(contentId = contentId, name = "profile one"))
        assertEquals("profile one", progressFlow.first()?.name)

        val profileTwoId = createAndSwitchToSecondProfile(manager)
        preferences.saveProgress(sampleProgress(contentId = contentId, name = "profile two"))

        val switched = progressFlow.awaitValue { it?.name == "profile two" }
        assertEquals(profileTwoId, manager.activeProfileId.value)
        assertEquals("profile two", switched?.name)
    }

    @Test
    fun `episode progress map query follows active profile switches`() = runTest {
        val manager = makeManager()
        val preferences = WatchProgressPreferences(factory, manager)
        val contentId = uniqueContentId("tt-series")
        val episodeMapFlow = preferences.getAllEpisodeProgress(contentId)
        val episodeFlow = preferences.getEpisodeProgress(contentId, season = 1, episode = 1)

        preferences.saveProgress(
            sampleProgress(
                contentId = contentId,
                name = "profile one episode",
                season = 1,
                episode = 1
            )
        )
        assertEquals("profile one episode", episodeMapFlow.first()[1 to 1]?.name)
        assertEquals("profile one episode", episodeFlow.first()?.name)

        createAndSwitchToSecondProfile(manager)
        preferences.saveProgress(
            sampleProgress(
                contentId = contentId,
                name = "profile two episode",
                season = 1,
                episode = 1
            )
        )

        val switched = episodeMapFlow.awaitValue { it[1 to 1]?.name == "profile two episode" }
        val switchedSingleEpisode = episodeFlow.awaitValue { it?.name == "profile two episode" }
        assertEquals("profile two episode", switched[1 to 1]?.name)
        assertEquals("profile two episode", switchedSingleEpisode?.name)
    }

    private suspend fun <T> Flow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) {
                first(predicate)
            }
        }

    private suspend fun createAndSwitchToSecondProfile(manager: ProfileManager): Int {
        manager.createProfile("Secondary", "#E53935")
        val profileId = manager.profiles.first { profiles -> profiles.size >= 2 }
            .first { profile -> profile.id != 1 }
            .id
        manager.setActiveProfile(profileId)
        return profileId
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
