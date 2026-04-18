package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCatalogSnapshotStoreTest {

    private val testPosterToken = "native"

    @Test
    fun `read restores persisted home snapshot for matching language`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver
        )

        val snapshot = sampleSnapshot()
        store.write(snapshot, testPosterToken)

        assertEquals(snapshot, store.read(testPosterToken))
    }

    @Test
    fun `read rejects persisted home snapshot when app language changes`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver
        )

        store.write(sampleSnapshot(), testPosterToken)
        localePrefs.edit().putString("locale_tag", "nl").apply()

        assertNull(store.read(testPosterToken))
    }

    @Test
    fun `read restores the snapshot for the active language without overwriting another language`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver
        )
        val englishSnapshot = sampleSnapshot()
        val dutchSnapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(sampleRow("addon", "dutch")),
            fullCatalogRows = listOf(sampleRow("addon", "dutch")),
            heroItems = sampleRow("addon", "dutch").items,
            orderedGroupKeys = listOf("addon_movie_dutch")
        )

        store.write(englishSnapshot, testPosterToken)
        localePrefs.edit().putString("locale_tag", "nl").apply()
        store.write(dutchSnapshot, testPosterToken)

        assertEquals(dutchSnapshot, store.read(testPosterToken))
        localePrefs.edit().putString("locale_tag", "en").apply()
        assertEquals(englishSnapshot, store.read(testPosterToken))
    }

    @Test
    fun `read preserves mixed trakt simkl addon ordered group keys`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver
        )

        val row = sampleRow("simkl", "simkl_tv_trending_today")
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf(
                "trakt_trending_movies",
                "simkl_tv_trending_today",
                "cinemeta_movie_popular"
            )
        )

        store.write(snapshot, testPosterToken)

        assertEquals(snapshot.orderedGroupKeys, store.read(testPosterToken)?.orderedGroupKeys)
    }

    @Test
    fun `explicit profile id keeps home snapshots isolated`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver
        )
        val profileOneSnapshot = sampleSnapshot()
        val profileTwoSnapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(sampleRow("simkl", "trending")),
            fullCatalogRows = listOf(sampleRow("simkl", "trending")),
            heroItems = sampleRow("simkl", "trending").items,
            orderedGroupKeys = listOf("simkl_trending")
        )

        store.write(profileOneSnapshot, testPosterToken, profileId = 1)
        store.write(profileTwoSnapshot, testPosterToken, profileId = 2)

        assertEquals(profileOneSnapshot, store.read(testPosterToken, profileId = 1))
        assertEquals(profileTwoSnapshot, store.read(testPosterToken, profileId = 2))
    }

    @Test
    fun `read rejects active poster provider snapshots with untagged posters`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver
        )

        store.write(sampleSnapshot(), "RPDB:12345")

        assertNull(store.read("RPDB:12345"))
    }

    @Test
    fun `read accepts active poster provider snapshots with matching poster tags`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver
        )
        val row = sampleRow("addon", "movies", posterProviderTag = "rpdb")
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )

        store.write(snapshot, "RPDB:12345")

        assertEquals(snapshot, store.read("RPDB:12345"))
    }

    private fun sampleSnapshot(): HomeCatalogSnapshotStore.Snapshot {
        val row = sampleRow("addon", "movies")
        return HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )
    }

    private fun sampleRow(
        addonId: String,
        catalogId: String,
        posterProviderTag: String? = null
    ): CatalogRow {
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
                    genres = listOf("Drama"),
                    posterProviderTag = posterProviderTag
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
        snapshotPrefs: InMemorySharedPreferences,
        expectedName: String,
        localePrefs: InMemorySharedPreferences
    ): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                when (firstArg<String>()) {
                    expectedName -> snapshotPrefs
                    "app_locale" -> localePrefs
                    else -> throw IllegalArgumentException("Unexpected prefs ${firstArg<String>()}")
                }
            }
        }
    }
}
