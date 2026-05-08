package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.data.repository.ContinueWatchingCanonicalKey
import com.nexio.tv.data.repository.ContinueWatchingRecord
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.IdentityConfidence
import com.nexio.tv.data.repository.ResumeIdentity
import com.nexio.tv.data.repository.StreamFetchIdentity
import com.nexio.tv.data.repository.StreamIdScheme
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.data.repository.ContinueWatchingSource
import com.nexio.tv.data.repository.TrackingIdentity
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrackingProvider
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
        val expectedDisplayMetadata = snapshot.displayMetadataByItemKey
            .mapValues { (_, metadata) -> metadata.copy(ratingSource = null) }

        store.write(snapshot)

        assertEquals(expectedDisplayMetadata, store.read()?.displayMetadataByItemKey)

        assertEquals(expectedDisplayMetadata, store.read()?.displayMetadataByItemKey)
    }

    @Test
    fun `read display metadata map tolerates legacy entries without rating source`() {
        val prefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = ContinueWatchingSnapshotStore(context, metadataStore)
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 5,
              "languageEpoch": 0,
              "languageTag": "en",
              "resumeItems": [],
              "nextUpItems": [],
              "traktUpNextItems": [],
              "scheduledReemit": [],
              "displayMetadataByItemKey": {
                "movie:tt123": {
                  "title":"Movie",
                  "logo":null,
                  "description":null,
                  "genres":[],
                  "releaseInfo":"2025",
                  "runtime":null,
                  "imdbRating":8.3,
                  "tomatoesRating":null,
                  "poster":null,
                  "posterProviderTag":null,
                  "backdrop":null
                }
              },
              "updatedAtMs": 100
            }
            """.trimIndent()
        ).commit()

        val metadata = store.read(profileId = 1)?.displayMetadataByItemKey?.get("movie:tt123")

        metadata.hashCode()
        assertEquals(TitleRatingSource.IMDB, metadata?.ratingSource)
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
    fun `write persists canonical records with stream and resume identities`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 3,
            positionMs = 5_000L,
            durationMs = 10_000L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(season = 2, number = 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.REMOTE,
            updatedAt = 20_000L,
            canonicalKey = ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = identity,
                season = 2,
                episode = 1,
                profileId = 1
            ),
            displayIdentity = identity,
            streamFetchIdentity = StreamFetchIdentity(
                contentId = "tt9794044",
                videoId = "tt9794044:2:1",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf("resolved imdb episode")
            ),
            trackingIdentity = TrackingIdentity(
                traktShowId = 10,
                traktEpisodeId = 20,
                traktPlaybackId = 30L,
                providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
            ),
            resumeIdentities = listOf(
                ResumeIdentity(
                    source = ContinueWatchingSource.LOCAL,
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1",
                    season = 2,
                    episode = 1,
                    positionMs = 4_000L,
                    durationMs = 10_000L,
                    progressPercent = 40f,
                    lastWatchedMs = 10_000L
                ),
                ResumeIdentity(
                    source = ContinueWatchingSource.TRAKT_PLAYBACK,
                    contentId = "tt9794044",
                    videoId = "tt9794044:2:1",
                    season = 2,
                    episode = 1,
                    positionMs = 5_000L,
                    durationMs = 10_000L,
                    progressPercent = 50f,
                    lastWatchedMs = 20_000L
                )
            ),
            primaryResumeLookupKey = "tt9794044|tt9794044:2:1|2|1",
            identityConfidence = IdentityConfidence.HIGH,
            identityWarnings = listOf("used alias"),
            languageTag = "en"
        )

        store.write(
            ContinueWatchingSnapshot(
                records = listOf(record),
                updatedAtMs = 30_000L
            )
        )

        val restored = store.read()

        assertEquals(listOf(record), restored?.records)
        assertEquals(record.streamFetchIdentity, restored?.records?.singleOrNull()?.streamFetchIdentity)
        assertEquals(record.resumeIdentities, restored?.records?.singleOrNull()?.resumeIdentities)
        assertEquals(record.primaryResumeLookupKey, restored?.records?.singleOrNull()?.primaryResumeLookupKey)
    }

    @Test
    fun `read accepts current snapshots without records`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 5,
              "languageEpoch": 1,
              "languageTag": "en",
              "resumeItems": [],
              "nextUpItems": [],
              "traktUpNextItems": [],
              "scheduledReemit": [],
              "displayMetadataByItemKey": {},
              "metadataSnapshotsByItemKey": {},
              "updatedAtMs": 100
            }
            """.trimIndent()
        ).commit()

        val restored = store.read()

        assertEquals(100L, restored?.updatedAtMs)
        assertEquals(emptyList<ContinueWatchingRecord>(), restored?.records)
    }

    @Test
    fun `read restores concrete resume identities from persisted records`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 5,
              "languageEpoch": 1,
              "languageTag": "en",
              "resumeItems": [],
              "nextUpItems": [],
              "traktUpNextItems": [],
              "scheduledReemit": [],
              "records": [
                {
                  "profileId": 1,
                  "parentId": "series:tvdb:393268",
                  "contentId": "series:tvdb:393268:s2e1",
                  "provider": "TRAKT",
                  "routingVersion": 3,
                  "positionMs": 5000,
                  "durationMs": 10000,
                  "episodeContext": { "season": 2, "number": 1 },
                  "clickTimeDisplayMetadata": null,
                  "source": "REMOTE",
                  "updatedAt": 20000,
                  "canonicalKey": null,
                  "displayIdentity": null,
                  "streamFetchIdentity": null,
                  "trackingIdentity": null,
                  "resumeIdentities": [
                    {
                      "source": "TRAKT_PLAYBACK",
                      "contentId": "tt9794044",
                      "videoId": "tt9794044:2:1",
                      "season": 2,
                      "episode": 1,
                      "positionMs": 5000,
                      "durationMs": 10000,
                      "progressPercent": 50.0,
                      "lastWatchedMs": 20000
                    }
                  ],
                  "primaryResumeLookupKey": "tt9794044|tt9794044:2:1|2|1",
                  "identityConfidence": "HIGH",
                  "identityWarnings": [],
                  "languageTag": "en"
                }
              ],
              "displayMetadataByItemKey": {},
              "metadataSnapshotsByItemKey": {},
              "updatedAtMs": 100
            }
            """.trimIndent()
        ).commit()

        val record = store.read()?.records?.singleOrNull()

        assertEquals("tt9794044|tt9794044:2:1|2|1", record?.resumeIdentities?.singleOrNull()?.lookupKey())
        assertEquals("tt9794044|tt9794044:2:1|2|1", record?.primaryResumeLookupKey)
    }

    @Test
    fun `explicit profile id keeps continue watching snapshots isolated`() {
        val profileOnePrefs = InMemorySharedPreferences()
        val profileTwoPrefs = InMemorySharedPreferences()
        val context = mockContext(
            preferencesByName = mapOf(
                "continue_watching_snapshot" to profileOnePrefs,
                "continue_watching_snapshot_p2" to profileTwoPrefs
            ),
            localePrefs = localePrefs("en")
        )
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        store.write(ContinueWatchingSnapshot(updatedAtMs = 1_000L), profileId = 1)
        store.write(ContinueWatchingSnapshot(updatedAtMs = 2_000L), profileId = 2)

        assertEquals(1_000L, store.read(profileId = 1)?.updatedAtMs)
        assertEquals(2_000L, store.read(profileId = 2)?.updatedAtMs)
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
        return mockContext(
            preferencesByName = mapOf(expectedName to prefs),
            localePrefs = localePrefs
        )
    }

    private fun mockContext(
        preferencesByName: Map<String, InMemorySharedPreferences>,
        localePrefs: InMemorySharedPreferences
    ): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                val name = firstArg<String>()
                when (name) {
                    in preferencesByName -> preferencesByName.getValue(name)
                    "app_locale" -> localePrefs
                    else -> throw IllegalArgumentException("Unexpected prefs $name")
                }
            }
        }
    }
}
