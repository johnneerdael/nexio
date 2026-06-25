package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyntheticHomeCatalogStoreTest {

    @Test
    fun `read restores persisted synthetic rows for matching language`() {
        val prefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SyntheticHomeCatalogStore(context, metadataStore)

        val snapshot = SyntheticHomeCatalogStore.Snapshot(
            traktGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "trakt_up_next",
                    rows = listOf(sampleRow("trakt", "up_next"))
                )
            ),
            simklGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "simkl_tv_trending_today",
                    rows = listOf(sampleRow("simkl", "simkl_tv_trending_today"))
                )
            ),
            mdbListGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "top:list",
                    rows = listOf(sampleRow("mdblist", "list_movies"))
                )
            )
        )

        store.write(snapshot)

        val restored = store.read()
        assertEquals(snapshot.traktGroups, restored?.traktGroups)
        assertEquals(snapshot.simklGroups, restored?.simklGroups)
        assertEquals(snapshot.mdbListGroups, restored?.mdbListGroups)

        val restoredAgain = store.read()
        assertEquals(snapshot.traktGroups, restoredAgain?.traktGroups)
        assertEquals(snapshot.simklGroups, restoredAgain?.simklGroups)
        assertEquals(snapshot.mdbListGroups, restoredAgain?.mdbListGroups)
    }

    @Test
    fun `read rejects persisted synthetic rows when app language changes`() {
        val prefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = SyntheticHomeCatalogStore(context, metadataStore)

        store.write(
            SyntheticHomeCatalogStore.Snapshot(
                traktGroups = listOf(
                    PersistedSyntheticCatalogGroup(
                        orderKey = "trakt_up_next",
                        rows = listOf(sampleRow("trakt", "up_next"))
                    )
                )
            )
        )

        localePrefs.edit().putString("locale_tag", "nl").apply()

        assertNull(store.read())
    }

    @Test
    fun `read restores synthetic rows for active language without overwriting another language`() {
        val prefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SyntheticHomeCatalogStore(context, metadataStore)
        val englishSnapshot = SyntheticHomeCatalogStore.Snapshot(
            traktGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "trakt_trending_movies",
                    rows = listOf(sampleRow("trakt", "trending_movies"))
                )
            )
        )
        val dutchSnapshot = SyntheticHomeCatalogStore.Snapshot(
            simklGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "simkl_tv_trending_today",
                    rows = listOf(sampleRow("simkl", "simkl_tv_trending_today"))
                )
            )
        )

        store.write(englishSnapshot)
        localePrefs.edit().putString("locale_tag", "nl").apply()
        store.write(dutchSnapshot)

        assertEquals(dutchSnapshot.simklGroups, store.read()?.simklGroups)
        localePrefs.edit().putString("locale_tag", "en").apply()
        assertEquals(englishSnapshot.traktGroups, store.read()?.traktGroups)
    }

    @Test
    fun `write persists canonical group keys`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = SyntheticHomeCatalogStore(context, metadataStore)

        store.write(
            SyntheticHomeCatalogStore.Snapshot(
                traktGroups = listOf(
                    PersistedSyntheticCatalogGroup(
                        orderKey = "trakt_trending_movies",
                        rows = listOf(sampleRow("trakt", "trending_movies"))
                    )
                )
            )
        )

        val raw = rawSnapshotJson(context)
        assertTrue(raw.contains("\"orderKey\":\"trakt_trending_movies\""))
        assertTrue(raw.contains("\"rows\""))
        assertTrue(raw.contains("\"traktGroups\""))
    }

    @Test
    fun `write persists simkl synthetic groups alongside trakt and mdblist`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = SyntheticHomeCatalogStore(context, metadataStore)

        store.write(
            SyntheticHomeCatalogStore.Snapshot(
                simklGroups = listOf(
                    PersistedSyntheticCatalogGroup(
                        orderKey = "simkl_anime_trending_month",
                        rows = listOf(sampleRow("simkl", "simkl_anime_trending_month"))
                    )
                )
            )
        )

        val raw = rawSnapshotJson(context)
        assertTrue(raw.contains("\"simklGroups\""))
        assertTrue(raw.contains("\"orderKey\":\"simkl_anime_trending_month\""))
    }

    @Test
    fun `write persists tmdb preference provenance`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = SyntheticHomeCatalogStore(context, metadataStore)
        val snapshot = SyntheticHomeCatalogStore.Snapshot(
            tmdbGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = TmdbCatalogIds.TRENDING_MOVIES,
                    rows = listOf(sampleRow("tmdb", TmdbCatalogIds.TRENDING_MOVIES))
                )
            ),
            tmdbIncludeAdult = true,
            tmdbHideUnreleasedDigital = false
        )

        store.write(snapshot)

        val raw = rawSnapshotJson(context)
        assertTrue(raw.contains("\"tmdbIncludeAdult\":true"))
        assertTrue(raw.contains("\"tmdbHideUnreleasedDigital\":false"))
        val restored = store.read()
        assertEquals(snapshot.tmdbGroups, restored?.tmdbGroups)
        assertEquals(snapshot.tmdbIncludeAdult, restored?.tmdbIncludeAdult)
        assertEquals(snapshot.tmdbHideUnreleasedDigital, restored?.tmdbHideUnreleasedDigital)
    }

    @Test
    fun `read decodes v4 snapshot without tmdb provenance as unknown`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SyntheticHomeCatalogStore(context, metadataStore)
        prefs.edit()
            .putString(
                "snapshot:p1:en",
                """
                {
                  "schemaVersion":4,
                  "languageEpoch":0,
                  "languageTag":"en",
                  "traktGroups":[],
                  "simklGroups":[],
                  "mdbListGroups":[],
                  "tmdbGroups":[]
                }
                """.trimIndent()
            )
            .commit()

        val restored = store.read()

        assertEquals(SyntheticHomeCatalogStore.Snapshot(), restored)
        assertNull(restored?.tmdbIncludeAdult)
        assertNull(restored?.tmdbHideUnreleasedDigital)
    }

    @Test
    fun `explicit profile id keeps synthetic snapshots isolated`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SyntheticHomeCatalogStore(context, metadataStore)
        val profileOneSnapshot = SyntheticHomeCatalogStore.Snapshot(
            traktGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "trakt_trending_movies",
                    rows = listOf(sampleRow("trakt", "trending_movies"))
                )
            )
        )
        val profileTwoSnapshot = SyntheticHomeCatalogStore.Snapshot(
            simklGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = "simkl_tv_trending_today",
                    rows = listOf(sampleRow("simkl", "simkl_tv_trending_today"))
                )
            )
        )

        store.write(profileOneSnapshot, profileId = 1)
        store.write(profileTwoSnapshot, profileId = 2)

        assertEquals(profileOneSnapshot.traktGroups, store.read(profileId = 1)?.traktGroups)
        assertEquals(profileTwoSnapshot.simklGroups, store.read(profileId = 2)?.simklGroups)
    }

    @Test
    fun `concurrent writes to same profile leave a readable snapshot`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SyntheticHomeCatalogStore(context, metadataStore)
        val start = CountDownLatch(1)
        val done = CountDownLatch(12)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(12) { index ->
            Thread {
                try {
                    start.await()
                    val snapshot = SyntheticHomeCatalogStore.Snapshot(
                        simklGroups = listOf(
                            PersistedSyntheticCatalogGroup(
                                orderKey = "simkl_group_$index",
                                rows = listOf(sampleRow("simkl", "simkl_group_$index"))
                            )
                        )
                    )
                    repeat(6) {
                        store.write(snapshot, profileId = 2)
                        checkNotNull(store.read(profileId = 2)) {
                            "Snapshot became unreadable during concurrent writes"
                        }
                    }
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    done.countDown()
                }
            }.start()
        }

        start.countDown()

        assertTrue("Timed out waiting for concurrent writers", done.await(10, TimeUnit.SECONDS))
        assertTrue(failures.joinToString(separator = "\n") { it.stackTraceToString() }, failures.isEmpty())
        assertTrue(store.read(profileId = 2)?.simklGroups.orEmpty().isNotEmpty())
        val snapshotDir = File(context.filesDir, "synthetic-home-catalog-v1")
        val leftoverTemps = snapshotDir.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty()
        assertTrue("Temp files left behind: ${leftoverTemps.toList()}", leftoverTemps.isEmpty())
    }

    @Test
    fun `public trakt fallback reuses only enabled public synthetic groups from another profile`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs("en"))
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SyntheticHomeCatalogStore(context, metadataStore)
        val profileOneSnapshot = SyntheticHomeCatalogStore.Snapshot(
            traktGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = TraktCatalogIds.TRENDING_MOVIES,
                    rows = listOf(sampleRow("trakt", TraktCatalogIds.TRENDING_MOVIES))
                ),
                PersistedSyntheticCatalogGroup(
                    orderKey = TraktCatalogIds.TRENDING_SHOWS,
                    rows = listOf(sampleRow("trakt", TraktCatalogIds.TRENDING_SHOWS))
                ),
                PersistedSyntheticCatalogGroup(
                    orderKey = TraktCatalogIds.POPULAR_MOVIES,
                    rows = listOf(sampleRow("trakt", TraktCatalogIds.POPULAR_MOVIES))
                ),
                PersistedSyntheticCatalogGroup(
                    orderKey = TraktCatalogIds.RECOMMENDED_MOVIES,
                    rows = listOf(sampleRow("trakt", TraktCatalogIds.RECOMMENDED_MOVIES))
                )
            ),
            simklGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = SimklCatalogIds.TV_TRENDING_TODAY,
                    rows = listOf(sampleRow("simkl", SimklCatalogIds.TV_TRENDING_TODAY))
                )
            )
        )

        store.write(profileOneSnapshot, profileId = 1)

        val fallback = store.readReusablePublicTraktSnapshot(
            profileId = 2,
            enabledCatalogs = setOf(
                TraktCatalogIds.TRENDING_MOVIES,
                TraktCatalogIds.TRENDING_SHOWS,
                TraktCatalogIds.RECOMMENDED_MOVIES
            )
        )

        assertEquals(
            listOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS),
            fallback?.traktGroups?.map { it.orderKey }
        )
        assertTrue(fallback?.simklGroups.orEmpty().isEmpty())
        assertTrue(fallback?.traktGroups.orEmpty().none { it.orderKey == TraktCatalogIds.POPULAR_MOVIES })
        assertTrue(fallback?.traktGroups.orEmpty().none { it.orderKey == TraktCatalogIds.RECOMMENDED_MOVIES })
    }

    @Test
    fun `public trakt fallback can reuse another profile synthetic groups from another language file`() {
        val prefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val context = mockContext(prefs, "synthetic_home_catalogs", localePrefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SyntheticHomeCatalogStore(context, metadataStore)
        val profileOneSnapshot = SyntheticHomeCatalogStore.Snapshot(
            traktGroups = listOf(
                PersistedSyntheticCatalogGroup(
                    orderKey = TraktCatalogIds.TRENDING_MOVIES,
                    rows = listOf(sampleRow("trakt", TraktCatalogIds.TRENDING_MOVIES))
                ),
                PersistedSyntheticCatalogGroup(
                    orderKey = TraktCatalogIds.TRENDING_SHOWS,
                    rows = listOf(sampleRow("trakt", TraktCatalogIds.TRENDING_SHOWS))
                )
            )
        )
        store.write(profileOneSnapshot, profileId = 1)
        localePrefs.edit().putString("locale_tag", "nl").apply()

        val fallback = store.readReusablePublicTraktSnapshot(
            profileId = 2,
            enabledCatalogs = setOf(
                TraktCatalogIds.TRENDING_MOVIES,
                TraktCatalogIds.TRENDING_SHOWS
            )
        )

        assertEquals(
            listOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS),
            fallback?.traktGroups?.map { it.orderKey }
        )
    }

    private fun sampleRow(addonId: String, catalogId: String): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = addonId,
            addonBaseUrl = "https://example.com/$addonId",
            catalogId = catalogId,
            catalogName = "Catalog $catalogId",
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "tt123",
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = "Sample",
                    poster = "poster",
                    posterShape = PosterShape.POSTER,
                    background = "background",
                    logo = "logo",
                    description = "description",
                    releaseInfo = "2025",
                    imdbRating = 8.1f,
                    genres = listOf("Drama")
                )
            )
        )
    }

    private fun localePrefs(tag: String): InMemorySharedPreferences {
        return InMemorySharedPreferences().also { prefs ->
            prefs.edit().putString("locale_tag", tag).apply()
        }
    }

    private fun rawSnapshotJson(
        context: Context,
        profileId: Int = 1,
        tag: String = "en"
    ): String {
        return File(context.filesDir, "synthetic-home-catalog-v1/p${profileId}_$tag.json").readText()
    }

    private fun mockContext(
        prefs: InMemorySharedPreferences,
        expectedName: String,
        localePrefs: InMemorySharedPreferences
    ): Context {
        val tempFilesDir = Files.createTempDirectory("synthetic-home-catalog-store-test").toFile()
        return mockk {
            every { getFilesDir() } returns tempFilesDir
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
