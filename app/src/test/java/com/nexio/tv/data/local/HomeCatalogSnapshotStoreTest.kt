package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.PersistedArtworkCandidate
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `read accepts active poster provider snapshots with untagged primary fallback posters`() {
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

        assertEquals(sampleSnapshot(), store.read("RPDB:12345"))
    }

    @Test
    fun `read rejects active poster provider snapshots with mismatched poster tags`() {
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
        val row = sampleRow("addon", "movies", posterProviderTag = "top_posters")
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )

        store.write(snapshot, "RPDB:12345")

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

    @Test
    fun `write sanitizes raw premium provider URLs from persisted JSON`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = InMemoryArtworkDecisionCache()
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "https://api.ratingposterdb.com/rpdb-secret/imdb/poster-default/tt123.jpg",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains("api.ratingposterdb.com"))
        assertFalse(raw.contains("rpdb-secret"))
        assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
    }

    @Test
    fun `read clears missing decision refs and tag`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = InMemoryArtworkDecisionCache()
        val decisionKey = ArtworkDecisionKey("missing-decision")
        cache.put(sampleArtworkDecision(decisionKey))
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/missing-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")
        cache.remove(decisionKey)

        val restored = store.read("RPDB:12345")
        assertClearedPosterFields(restored)
    }

    @Test
    fun `read preserves valid decision refs backed by cache`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = InMemoryArtworkDecisionCache()
        cache.put(sampleArtworkDecision(ArtworkDecisionKey("valid-decision")))
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/valid-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        assertEquals(snapshot, store.read("RPDB:12345"))
    }

    @Test
    fun `builder emits a profile scoped home catalog rail with canonical media keys`() {
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
        val row = sampleRow("addon", "tmdb:popular:movies")
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("tmdb:popular:movies")
        )

        val membership = store.buildRailMemberships(snapshot, testPosterToken, profileId = 7).single()
        assertEquals("profile:7:home:catalog:tmdb:popular:movies", membership.rail.railKey)
        assertEquals("movie:imdb:tt123", membership.items.single().mediaKey)
        assertEquals("movie:imdb:tt123", membership.mediaIdentities.single().mediaKey)
        assertTrue(membership.externalIds.any { it.provider == "IMDB" && it.externalId == "tt123" })
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

    private fun sampleSnapshotWithPoster(
        poster: String,
        posterProviderTag: String?
    ): HomeCatalogSnapshotStore.Snapshot {
        val row = sampleRow("addon", "movies", poster = poster, posterProviderTag = posterProviderTag)
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
        poster: String? = "poster",
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
                    poster = poster,
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

    private fun sampleArtworkDecision(key: ArtworkDecisionKey): ArtworkDecision =
        ArtworkDecision(
            decisionKey = key,
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt123"),
            canonicalContentId = "imdb:tt123",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.Placeholder,
                sourceRole = ArtworkSourceRole.PLACEHOLDER,
                sourceHash = null,
                redactedSourceForTrace = null,
                providerTemplate = null,
                priority = 1
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settings",
            credentialHash = "credential",
            createdAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300
        )

    private fun persistedSnapshotJson(prefs: InMemorySharedPreferences): String =
        prefs.getAll().values.single() as String

    private fun assertClearedPosterFields(snapshot: HomeCatalogSnapshotStore.Snapshot?) {
        val items = buildList {
            add(snapshot?.catalogRows?.single()?.items?.single())
            add(snapshot?.fullCatalogRows?.single()?.items?.single())
            add(snapshot?.heroItems?.single())
        }
        items.forEach { item ->
            assertNull(item?.poster)
            assertNull(item?.posterProviderTag)
        }
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
