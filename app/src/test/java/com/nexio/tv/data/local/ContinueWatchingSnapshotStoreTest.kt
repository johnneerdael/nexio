package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingSnapshotStoreTest {

    @Test
    fun `read restores persisted display metadata for matching language`() {
        val prefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        val snapshot = ContinueWatchingSnapshot(
            displayMetadataByItemKey = mapOf(
                "movie:tt123" to HomeDisplayMetadata(
                    title = "Localized Movie",
                    description = "Overview"
                )
            ),
            updatedAtMs = 100L
        )

        store.write(snapshot)

        assertEquals(snapshot.displayMetadataByItemKey, store.read()?.displayMetadataByItemKey)

        assertEquals(snapshot.displayMetadataByItemKey, store.read()?.displayMetadataByItemKey)
    }

    @Test
    fun `read rejects persisted continue watching snapshot when app language changes`() {
        val prefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 3
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        store.write(
            ContinueWatchingSnapshot(
                displayMetadataByItemKey = mapOf(
                    "movie:tt123" to HomeDisplayMetadata(
                        title = "Localized Movie",
                        description = "Overview"
                    )
                ),
                updatedAtMs = 100L
            )
        )

        localePrefs.edit().putString("locale_tag", "nl").apply()

        assertNull(store.read())
    }

    @Test
    fun `write persists generic resumes and next up activity timestamps`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        val snapshot = ContinueWatchingSnapshot(
            resumeItems = listOf(
                WatchProgress(
                    contentId = "show-a",
                    contentType = "series",
                    name = "Show A",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "show-a:1:2",
                    season = 1,
                    episode = 2,
                    episodeTitle = "Episode 2",
                    position = 50L,
                    duration = 100L,
                    lastWatched = 1_000L,
                    progressPercent = 50f
                )
            ),
            nextUpItems = listOf(
                TrackingNextUpEntry(
                    contentId = "show-b",
                    name = "Show B",
                    season = 2,
                    episode = 3,
                    episodeTitle = "Episode 3",
                    videoId = "show-b:2:3",
                    firstAired = "2026-03-23T00:00:00.000Z",
                    firstAiredMs = 900L,
                    activityAtMs = 1_500L
                )
            ),
            traktUpNextItems = listOf(
                TrackingNextUpEntry(
                    contentId = "show-b",
                    name = "Show B",
                    season = 2,
                    episode = 3,
                    episodeTitle = "Episode 3",
                    videoId = "show-b:2:3",
                    firstAired = "2026-03-23T00:00:00.000Z",
                    firstAiredMs = 900L,
                    activityAtMs = 1_500L
                )
            ),
            updatedAtMs = 2_000L
        )

        store.write(snapshot)

        val restored = store.read()
        assertEquals(snapshot.resumeItems, restored?.resumeItems)
        assertEquals(1_500L, restored?.nextUpItems?.singleOrNull()?.activityAtMs)
        assertEquals(1_500L, restored?.traktUpNextItems?.singleOrNull()?.activityAtMs)
    }

    @Test
    fun `write persists scheduled reemit and tvdb timing fields`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        val withheld = TrackingNextUpEntry(
            contentId = "show-future",
            name = "Show Future",
            season = 1,
            episode = 4,
            episodeTitle = "Episode 4",
            videoId = "show-future:1:4",
            firstAired = "2026-04-16",
            firstAiredMs = 2_000L,
            activityAtMs = 1_500L,
            tvdbAvailabilityInstantMs = 10_000L,
            tvdbAvailabilityPrecision = TvdbAirAvailabilityPrecision.EXACT_INSTANT,
            tvdbAvailabilitySourceZoneId = "America/New_York",
            tvdbAvailabilitySourcePolicy = "us_network_eastern",
            tvdbAvailabilityDiagnosticReason = TvdbAirAvailabilityDiagnosticReason.MISSING_AIRS_TIME,
            tvdbAvailabilityDeviceLocalDateTime = "2026-04-17T02:00"
        )

        store.write(
            ContinueWatchingSnapshot(
                scheduledReemit = listOf(withheld),
                updatedAtMs = 2_000L
            )
        )

        val restored = store.read()

        assertEquals(listOf(withheld), restored?.scheduledReemit)
        val restoredEntry = restored?.scheduledReemit?.singleOrNull()
        assertEquals(10_000L, restoredEntry?.tvdbAvailabilityInstantMs)
        assertEquals(TvdbAirAvailabilityPrecision.EXACT_INSTANT, restoredEntry?.tvdbAvailabilityPrecision)
        assertEquals("America/New_York", restoredEntry?.tvdbAvailabilitySourceZoneId)
        assertEquals("us_network_eastern", restoredEntry?.tvdbAvailabilitySourcePolicy)
        assertEquals(TvdbAirAvailabilityDiagnosticReason.MISSING_AIRS_TIME, restoredEntry?.tvdbAvailabilityDiagnosticReason)
        assertEquals("2026-04-17T02:00", restoredEntry?.tvdbAvailabilityDeviceLocalDateTime)
    }

    @Test
    fun `read rejects legacy snapshot payloads from before language-aware versioning`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        val legacyPayload = JsonObject().apply {
            addProperty("schemaVersion", 2)
            addProperty("languageEpoch", 1)
            add(
                "movieProgressItems",
                com.google.gson.Gson().toJsonTree(
                    listOf(
                        WatchProgress(
                            contentId = "movie-a",
                            contentType = "movie",
                            name = "Movie A",
                            poster = null,
                            backdrop = null,
                            logo = null,
                            videoId = "movie-a",
                            season = null,
                            episode = null,
                            episodeTitle = null,
                            position = 20L,
                            duration = 100L,
                            lastWatched = 1_000L,
                            progressPercent = 20f
                        )
                    )
                )
            )
            add("nextUpItems", com.google.gson.JsonArray())
            add("displayMetadataByItemKey", JsonObject())
            addProperty("updatedAtMs", 1_000L)
        }

        prefs.edit().putString("snapshot", legacyPayload.toString()).apply()

        assertNull(store.read())
    }

    private fun localePrefs(tag: String): InMemorySharedPreferences {
        return InMemorySharedPreferences().also { prefs ->
            prefs.edit().putString("locale_tag", tag).apply()
        }
    }

    private fun mockContext(
        prefs: InMemorySharedPreferences,
        expectedName: String,
        localePrefs: InMemorySharedPreferences
    ): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                when (firstArg<String>()) {
                    expectedName -> prefs
                    "app_locale" -> localePrefs
                    else -> throw IllegalArgumentException("Unexpected prefs ${firstArg<String>()}")
                }
            }
        }
    }
}
