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

        assertEquals(snapshot, store.read())

        assertEquals(snapshot, store.read())
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

        assertEquals(dutchSnapshot, store.read())
        localePrefs.edit().putString("locale_tag", "en").apply()
        assertEquals(englishSnapshot, store.read())
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

        val raw = prefs.all.values.singleOrNull()?.toString().orEmpty()
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

        val raw = prefs.all.values.singleOrNull()?.toString().orEmpty()
        assertTrue(raw.contains("\"simklGroups\""))
        assertTrue(raw.contains("\"orderKey\":\"simkl_anime_trending_month\""))
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

        assertEquals(profileOneSnapshot, store.read(profileId = 1))
        assertEquals(profileTwoSnapshot, store.read(profileId = 2))
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
