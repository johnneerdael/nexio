package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDecisionCacheDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionCacheSnapshotDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionLookupResult
import com.nexio.tv.core.artwork.ArtworkDecisionStoreLoadState
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityResult
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.PersistedArtworkCandidate
import com.nexio.tv.core.integration.RecordingTraceSink
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
import java.io.File
import java.nio.file.Files

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
    fun `read ignores active poster provider snapshots with mismatched poster tags`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val traceSink = RecordingTraceSink()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            traceSink = traceSink
        )
        val row = sampleRow("addon", "movies", posterProviderTag = "top_posters")
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )

        store.write(snapshot, "RPDB:12345")

        assertEquals(snapshot, store.read("RPDB:12345"))

        val mismatchPayload = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_provider_tag_mismatch_ignored" }
            .map { event -> event.payload as Map<*, *> }
            .single()
        assertEquals(3, mismatchPayload["mismatchCount"])
        assertEquals("top_posters=3", mismatchPayload["providerTags"])
        assertEquals("rpdb", mismatchPayload["requiredPosterProviderTag"])

        val rehydrateRequests = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_artwork_rehydrate_requested" }
            .map { event -> event.payload as Map<*, *> }
            .filter { payload -> payload["reason"] == "poster_provider_tag_mismatch" }
        assertEquals(3, rehydrateRequests.size)
        assertTrue(rehydrateRequests.all { payload -> payload["providerTag"] == "top_posters" })
        assertTrue(rehydrateRequests.all { payload -> payload["requiredProviderTag"] == "rpdb" })
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
    fun `write sanitizes raw premium provider URL host variants from persisted JSON`() {
        val cases = listOf(
            Triple(
                "http://api.ratingposterdb.com/rpdb-secret/imdb/poster-default/tt123.jpg",
                "api.ratingposterdb.com",
                "rpdb"
            ),
            Triple(
                "https://api.ratingposterdb.com:443/rpdb-secret/imdb/poster-default/tt123.jpg",
                "api.ratingposterdb.com",
                "rpdb"
            ),
            Triple(
                "http://api.top-posters.com/top-secret/imdb/poster-default/tt123.jpg",
                "api.top-posters.com",
                "top_posters"
            ),
            Triple(
                "https://api.top-posters.com:443/top-secret/imdb/poster-default/tt123.jpg",
                "api.top-posters.com",
                "top_posters"
            )
        )

        cases.forEachIndexed { index, (posterUrl, host, providerTag) ->
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
                poster = posterUrl,
                posterProviderTag = providerTag
            )

            store.write(snapshot, "RPDB:12345")

            val raw = persistedSnapshotJson(snapshotPrefs)
            assertFalse("case $index should not persist premium host", raw.contains(host))
            assertFalse("case $index should not persist premium key", raw.contains("secret"))
            assertFalse("case $index should clear provider tag", raw.contains("\"posterProviderTag\":\"$providerTag\""))
        }
    }

    @Test
    fun `write sanitizes legacy integration poster refs from persisted JSON`() {
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
            poster = "integration-poster://fetch?provider=RPDB",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains("integration-poster://"))
        assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
    }

    @Test
    fun `snapshot_write_preserves_orphaned_decision_ref_for_rehydration`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val traceSink = RecordingTraceSink()
        val decisionKey = ArtworkDecisionKey("missing-decision")
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
                "nexio-artwork://decision/missing-decision" to
                    ArtworkReferenceIntegrityResult.OrphanedDecisionRef(decisionKey, "missing_authoritative_no_asset")
            ),
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/missing-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertTrue(raw.contains("nexio-artwork://decision/missing-decision"))
        assertTrue(raw.contains("\"posterProviderTag\":\"rpdb\""))
        assertPosterFieldsPreserved(store.read("RPDB:12345"), "nexio-artwork://decision/missing-decision", "rpdb")
        assertTrue(
            traceSink.events.any { event ->
                event.eventType == "home.snapshot_artwork_rehydrate_requested" &&
                    (event.payload as Map<*, *>)["lookupResultType"] == "orphaned_decision_ref"
            }
        )
    }

    @Test
    fun `snapshot_write_prefers_asset_ref_when_reverse_index_recovers_asset`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val decisionKey = ArtworkDecisionKey("recoverable-decision")
        val assetKey = ArtworkAssetKey("recoverable-asset")
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
                "nexio-artwork://decision/recoverable-decision" to
                    ArtworkReferenceIntegrityResult.RecoverableAssetForDecision(decisionKey, assetKey),
                "nexio-artwork://asset/recoverable-asset" to
                    ArtworkReferenceIntegrityResult.ValidAsset(assetKey)
            )
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/recoverable-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains("nexio-artwork://decision/recoverable-decision"))
        assertTrue(raw.contains("nexio-artwork://asset/recoverable-asset"))
        assertPosterFieldsPreserved(store.read("RPDB:12345"), "nexio-artwork://asset/recoverable-asset", "rpdb")
    }

    @Test
    fun `snapshot_write_preserves_unknown_decision_ref`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val decisionKey = ArtworkDecisionKey("unknown-decision")
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
                "nexio-artwork://decision/unknown-decision" to
                    ArtworkReferenceIntegrityResult.UnknownDecisionRef(decisionKey, "decision_cache_not_authoritative")
            )
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/unknown-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        assertPosterFieldsPreserved(store.read("RPDB:12345"), "nexio-artwork://decision/unknown-decision", "rpdb")
    }

    @Test
    fun `snapshot_write_never_writes_poster_null_with_provider_tag`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
                "nexio-artwork://decision" to ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key")
            )
        )
        val invalidArtworkRow = sampleRow(
            addonId = "addon",
            catalogId = "invalid",
            poster = "nexio-artwork://decision",
            posterProviderTag = "rpdb"
        )
        val blankPosterHero = invalidArtworkRow.items.single().copy(poster = " ", posterProviderTag = "rpdb")
        val nullPosterFullRow = invalidArtworkRow.copy(
            catalogId = "null",
            items = listOf(invalidArtworkRow.items.single().copy(poster = null, posterProviderTag = "rpdb"))
        )
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(invalidArtworkRow),
            fullCatalogRows = listOf(nullPosterFullRow),
            heroItems = listOf(blankPosterHero)
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
        assertFalse(raw.contains("nexio-artwork://decision"))
    }

    @Test
    fun `snapshot_write_clears_malformed_decision_child_ref_with_provider_tag`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val malformedRef = "nexio-artwork://decision/artwork-decision:bad"
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = NonAuthoritativeArtworkDecisionCache(),
            artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
                malformedRef to ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key")
            )
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = malformedRef,
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains(malformedRef))
        assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
        assertClearedPosterFields(store.read("RPDB:12345"))
    }

    @Test
    fun `snapshot_write_clears_invalid_simple_decision_child_ref_with_provider_tag`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val invalidRef = "nexio-artwork://decision/bad"
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
                invalidRef to ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key")
            )
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = invalidRef,
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains(invalidRef))
        assertFalse(raw.contains("\"posterProviderTag\":\"rpdb\""))
        assertClearedPosterFields(store.read("RPDB:12345"))
    }

    @Test
    fun `snapshot_write_rejects_backdrop_ref_in_poster_slot`() {
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
        val backdropRef =
            "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1"
        val snapshot = sampleSnapshotWithPoster(
            poster = backdropRef,
            posterProviderTag = "tvdb"
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains(backdropRef))
        assertClearedPosterFields(store.read("RPDB:12345"))
    }

    @Test
    fun `snapshot_write_rejects_poster_ref_in_background_slot`() {
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
        val poster = "https://images.example/poster.jpg"
        val backgroundRef =
            "nexio-artwork://asset/artwork-asset:TVDB:poster:urlHash:abc:variant:none:imageLang:en:policy:1"
        val row = sampleRow(
            addonId = "addon",
            catalogId = "movies",
            poster = poster,
            posterProviderTag = "rpdb",
            background = backgroundRef
        )
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains(backgroundRef))
        assertPosterFieldsPreserved(store.read("RPDB:12345"), poster, "rpdb")
        assertBackgroundFieldsCleared(store.read("RPDB:12345"))
    }

    @Test
    fun `snapshot_write_rejects_backdrop_ref_in_logo_slot`() {
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
        val poster = "https://images.example/poster.jpg"
        val logoRef =
            "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1"
        val row = sampleRow(
            addonId = "addon",
            catalogId = "movies",
            poster = poster,
            posterProviderTag = "rpdb",
            logo = logoRef
        )
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertFalse(raw.contains(logoRef))
        assertPosterFieldsPreserved(store.read("RPDB:12345"), poster, "rpdb")
        assertLogoFieldsCleared(store.read("RPDB:12345"))
    }

    @Test
    fun `snapshot_write_preserves_type_correct_artwork_refs`() {
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
        val posterRef =
            "nexio-artwork://asset/artwork-asset:TVDB:poster:urlHash:posterhash:variant:none:imageLang:en:policy:1"
        val backgroundRef =
            "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:backdrophash:variant:none:imageLang:en:policy:1"
        val logoRef =
            "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:logohash:variant:none:imageLang:en:policy:1"
        val row = sampleRow(
            addonId = "addon",
            catalogId = "movies",
            poster = posterRef,
            posterProviderTag = "tvdb",
            background = backgroundRef,
            logo = logoRef
        )
        val snapshot = HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )

        store.write(snapshot, "RPDB:12345")

        val raw = persistedSnapshotJson(snapshotPrefs)
        assertTrue(raw.contains(posterRef))
        assertTrue(raw.contains(backgroundRef))
        assertTrue(raw.contains(logoRef))
        val restored = store.read("RPDB:12345")
        assertPosterFieldsPreserved(restored, posterRef, "tvdb")
        assertBackgroundFieldsPreserved(restored, backgroundRef)
        assertLogoFieldsPreserved(restored, logoRef)
    }

    @Test
    fun `snapshot_write_wrong_type_trace_uses_hashes_not_raw_refs`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val traceSink = RecordingTraceSink()
        val wrongTypeRef =
            "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:secretref:variant:none:imageLang:en:policy:1"
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = wrongTypeRef,
            posterProviderTag = "tvdb"
        )

        store.write(snapshot, "RPDB:12345")

        val writeBarrierPayloads = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_write_barrier_repaired" }
            .map { event -> event.payload as Map<*, *> }
            .filter { payload -> payload["reason"] == "wrong_artwork_type" }
        assertEquals(3, writeBarrierPayloads.size)
        assertTrue(writeBarrierPayloads.all { payload -> payload["field"] == "poster" })
        assertTrue(writeBarrierPayloads.all { payload -> (payload["assetKeyHash"] as String).isNotBlank() })
        assertTrue(writeBarrierPayloads.all { payload -> payload["destructive"] == true })
        assertTrue(writeBarrierPayloads.all { payload -> payload["posterProviderTagAction"] == "clear" })
        assertFalse(writeBarrierPayloads.joinToString("\n").contains(wrongTypeRef))
        assertFalse(writeBarrierPayloads.joinToString("\n").contains("secretref"))
    }

    @Test
    fun `snapshot write barrier redacts unknown validator reasons from trace payloads`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val traceSink = RecordingTraceSink()
        val invalidRef = "nexio-artwork://decision/raw-secret-decision-key-or-url"
        val rawReason = "raw-secret-decision-key-or-url"
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkReferenceIntegrityValidator = FakeArtworkReferenceIntegrityValidator(
                invalidRef to ArtworkReferenceIntegrityResult.Invalid(rawReason)
            ),
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = invalidRef,
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val writeBarrierPayloads = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_write_barrier_repaired" }
            .map { event -> event.payload as Map<*, *> }
        assertEquals(3, writeBarrierPayloads.size)
        assertTrue(writeBarrierPayloads.all { payload -> payload["reason"] == "validator_reason_redacted" })
        assertFalse(writeBarrierPayloads.joinToString("\n").contains(rawReason))
    }

    @Test
    fun `authoritative missing preserves decision refs and tag and requests rehydration`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = InMemoryArtworkDecisionCache()
        val decisionKey = ArtworkDecisionKey("missing-decision")
        cache.put(sampleArtworkDecision(decisionKey))
        val traceSink = RecordingTraceSink()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache,
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/missing-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")
        cache.remove(decisionKey)

        val restored = store.read("RPDB:12345")
        assertPosterFieldsPreserved(restored, "nexio-artwork://decision/missing-decision", "rpdb")

        val rehydrateRequests = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_artwork_rehydrate_requested" }
            .map { event -> event.payload as Map<*, *> }
            .filter { payload -> payload["reason"] == "missing_decision_authoritative" }
        assertEquals(3, rehydrateRequests.size)
        assertTrue(rehydrateRequests.all { payload -> payload["posterKind"] == "decision" })
        assertTrue(rehydrateRequests.all { payload -> payload["providerTag"] == "rpdb" })
        assertTrue(rehydrateRequests.all { payload -> (payload["decisionKeyHash"] as String).isNotBlank() })
        assertTrue(rehydrateRequests.none { payload ->
            (payload["decisionKeyHash"] as String).contains("missing-decision")
        })
    }

    @Test
    fun `non-authoritative cache preserves decision refs and provider tags`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = NonAuthoritativeArtworkDecisionCache()
        val traceSink = RecordingTraceSink()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache,
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/non-authoritative-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val restored = store.read("RPDB:12345")
        assertPosterFieldsPreserved(restored, "nexio-artwork://decision/non-authoritative-decision", "rpdb")

        val rehydratePayload = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_artwork_rehydrate_requested" }
            .map { event -> event.payload as Map<*, *> }
            .first { payload -> payload["reason"] == "decision_cache_not_authoritative" }
        assertEquals("decision", rehydratePayload["posterKind"])
        assertEquals("rpdb", rehydratePayload["providerTag"])
        assertTrue((rehydratePayload["decisionKeyHash"] as String).isNotBlank())
        assertFalse((rehydratePayload["decisionKeyHash"] as String).contains("non-authoritative-decision"))
    }

    @Test
    fun `lookup failure preserves decision ref and requests hydration`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = LookupFailedArtworkDecisionCache()
        val traceSink = RecordingTraceSink()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache,
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/failed-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")

        val restored = store.read("RPDB:12345")
        assertPosterFieldsPreserved(restored, "nexio-artwork://decision/failed-decision", "rpdb")

        val rehydratePayload = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_artwork_rehydrate_requested" }
            .map { event -> event.payload as Map<*, *> }
            .first { payload -> payload["reason"] == "lookup_failed" }
        assertEquals("decision", rehydratePayload["posterKind"])
        assertEquals("rpdb", rehydratePayload["providerTag"])
        assertTrue((rehydratePayload["decisionKeyHash"] as String).isNotBlank())
        assertFalse((rehydratePayload["decisionKeyHash"] as String).contains("failed-decision"))
    }

    @Test
    fun `provider tag mismatch does not reject non-authoritative preserved decision ref`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = NonAuthoritativeArtworkDecisionCache()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/mismatched-provider-decision",
            posterProviderTag = "top_posters"
        )

        store.write(snapshot, "RPDB:12345")

        val restored = store.read("RPDB:12345")
        assertPosterFieldsPreserved(
            restored,
            "nexio-artwork://decision/mismatched-provider-decision",
            "top_posters"
        )
    }

    @Test
    fun `provider tag mismatch preserves found decision ref and requests hydration`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = InMemoryArtworkDecisionCache()
        cache.put(sampleArtworkDecision(ArtworkDecisionKey("found-mismatched-provider-decision")))
        val traceSink = RecordingTraceSink()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache,
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/found-mismatched-provider-decision",
            posterProviderTag = "top_posters"
        )

        store.write(snapshot, "RPDB:12345")

        assertEquals(snapshot, store.read("RPDB:12345"))

        val mismatchPayload = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_provider_tag_mismatch_ignored" }
            .map { event -> event.payload as Map<*, *> }
            .single()
        assertEquals(3, mismatchPayload["mismatchCount"])
        assertEquals("decision=3", mismatchPayload["posterKinds"])

        val rehydratePayload = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_artwork_rehydrate_requested" }
            .map { event -> event.payload as Map<*, *> }
            .first { payload -> payload["reason"] == "poster_provider_tag_mismatch" }
        assertEquals("decision", rehydratePayload["posterKind"])
        assertEquals("top_posters", rehydratePayload["providerTag"])
        assertEquals("rpdb", rehydratePayload["requiredProviderTag"])
        assertTrue((rehydratePayload["decisionKeyHash"] as String).isNotBlank())
        assertFalse((rehydratePayload["decisionKeyHash"] as String).contains("found-mismatched-provider-decision"))
    }

    @Test
    fun `read logs missing decision ref rehydration without sanitization`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = InMemoryArtworkDecisionCache()
        val decisionKey = ArtworkDecisionKey("missing-decision-for-trace")
        cache.put(sampleArtworkDecision(decisionKey))
        val traceSink = RecordingTraceSink()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache,
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/missing-decision-for-trace",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")
        cache.remove(decisionKey)
        store.read("RPDB:12345")

        val sanitizeEvents = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_sanitize_artwork" }
        assertEquals(0, sanitizeEvents.size)

        val rehydrateRequests = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_artwork_rehydrate_requested" }
            .map { event -> event.payload as Map<*, *> }
            .filter { payload -> payload["reason"] == "missing_decision_authoritative" }
        assertEquals(3, rehydrateRequests.size)
        assertTrue(rehydrateRequests.all { payload -> payload["posterKind"] == "decision" })
        assertTrue(rehydrateRequests.all { payload -> payload["providerTag"] == "rpdb" })
    }

    @Test
    fun `read logs decision lookup diagnostics when snapshot decision is missing`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val cache = DiagnosticArtworkDecisionCache(
            diagnostics = ArtworkDecisionCacheSnapshotDiagnostics(
                loaded = true,
                decisionCount = 748,
                linkCount = 2,
                storeFilePresent = true,
                storeFileReadable = true,
                storeFileBytes = 81_920L,
                lastLoadSuccess = true,
                lastLoadReason = null,
                lastLoadErrorClass = null,
                droppedDecisionCount = 0,
                loadStateName = "LoadedAuthoritative",
                authoritative = true,
                quarantinedDecisionCount = 0,
                errorTopFrame = null
            )
        )
        val decisionKey = ArtworkDecisionKey("diagnostic-missing-decision")
        cache.put(sampleArtworkDecision(decisionKey))
        val traceSink = RecordingTraceSink()
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore,
            posterRatingsUrlResolver = posterResolver,
            artworkDecisionCache = cache,
            traceSink = traceSink
        )
        val snapshot = sampleSnapshotWithPoster(
            poster = "nexio-artwork://decision/diagnostic-missing-decision",
            posterProviderTag = "rpdb"
        )

        store.write(snapshot, "RPDB:12345")
        cache.remove(decisionKey)
        store.read("RPDB:12345")

        val lookupPayload = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_decision_lookup" }
            .first { event -> (event.payload as Map<*, *>)["missingDecisionCount"] == 3 }
            .payload as Map<*, *>
        assertEquals("snapshot", lookupPayload["scope"])
        assertEquals(3, lookupPayload["decisionLookupCount"])
        assertEquals(0, lookupPayload["decisionFoundCount"])
        assertEquals(3, lookupPayload["missingDecisionCount"])
        assertEquals(0, lookupPayload["cacheNotAuthoritativeCount"])
        assertEquals(0, lookupPayload["lookupErrorCount"])
        assertEquals(true, lookupPayload["cacheLoaded"])
        assertEquals(748, lookupPayload["cacheDecisionCount"])
        assertEquals(2, lookupPayload["cacheLinkCount"])
        assertEquals(true, lookupPayload["storeFilePresent"])
        assertEquals(true, lookupPayload["storeFileReadable"])
        assertEquals(81_920L, lookupPayload["storeFileBytes"])
        assertEquals(true, lookupPayload["lastLoadSuccess"])
        assertEquals(null, lookupPayload["lastLoadReason"])
        assertEquals(null, lookupPayload["lastLoadErrorClass"])
        assertEquals(0, lookupPayload["droppedDecisionCount"])
        assertEquals(true, lookupPayload["authoritative"])
        assertEquals("LoadedAuthoritative", lookupPayload["loadState"])
        assertEquals(0, lookupPayload["quarantinedDecisionCount"])
        assertEquals(null, lookupPayload["errorTopFrame"])
        assertEquals(3, lookupPayload["rehydrateRequestCount"])
        assertEquals(
            "found=0|missing_authoritative=3|cache_not_authoritative=0|lookup_failed=0",
            lookupPayload["lookupResultTypes"]
        )
        assertTrue((lookupPayload["missingDecisionSamples"] as String).contains("catalogRows[0].items[0]:decision:rpdb"))

        val rehydrateRequests = traceSink.events
            .filter { event -> event.eventType == "home.snapshot_artwork_rehydrate_requested" }
            .map { event -> event.payload as Map<*, *> }
            .filter { payload -> payload["reason"] == "missing_decision_authoritative" }
        assertEquals(3, rehydrateRequests.size)
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
        poster: String?,
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
        posterProviderTag: String? = null,
        background: String? = "background",
        logo: String? = "logo"
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
                    background = background,
                    logo = logo,
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

    private fun persistedSnapshotJson(prefs: InMemorySharedPreferences): String {
        val filesDir = filesDirsByPrefs[prefs]
            ?: error("No filesDir registered for InMemorySharedPreferences instance")
        val snapshotDir = File(filesDir, "home-catalog-snapshot-v1")
        val files = snapshotDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?: emptyList()
        require(files.size == 1) {
            "Expected exactly one snapshot file in ${snapshotDir.absolutePath}, found ${files.map { it.name }}"
        }
        return files.single().readText()
    }

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

    private fun assertPosterFieldsPreserved(
        snapshot: HomeCatalogSnapshotStore.Snapshot?,
        expectedPoster: String,
        expectedPosterProviderTag: String
    ) {
        val items = buildList {
            add(snapshot?.catalogRows?.single()?.items?.single())
            add(snapshot?.fullCatalogRows?.single()?.items?.single())
            add(snapshot?.heroItems?.single())
        }
        items.forEach { item ->
            assertEquals(expectedPoster, item?.poster)
            assertEquals(expectedPosterProviderTag, item?.posterProviderTag)
        }
    }

    private fun assertBackgroundFieldsCleared(snapshot: HomeCatalogSnapshotStore.Snapshot?) {
        snapshotItems(snapshot).forEach { item ->
            assertNull(item?.background)
        }
    }

    private fun assertBackgroundFieldsPreserved(
        snapshot: HomeCatalogSnapshotStore.Snapshot?,
        expectedBackground: String
    ) {
        snapshotItems(snapshot).forEach { item ->
            assertEquals(expectedBackground, item?.background)
        }
    }

    private fun assertLogoFieldsCleared(snapshot: HomeCatalogSnapshotStore.Snapshot?) {
        snapshotItems(snapshot).forEach { item ->
            assertNull(item?.logo)
        }
    }

    private fun assertLogoFieldsPreserved(
        snapshot: HomeCatalogSnapshotStore.Snapshot?,
        expectedLogo: String
    ) {
        snapshotItems(snapshot).forEach { item ->
            assertEquals(expectedLogo, item?.logo)
        }
    }

    private fun snapshotItems(snapshot: HomeCatalogSnapshotStore.Snapshot?): List<MetaPreview?> {
        return buildList {
            add(snapshot?.catalogRows?.single()?.items?.single())
            add(snapshot?.fullCatalogRows?.single()?.items?.single())
            add(snapshot?.heroItems?.single())
        }
    }

    private fun localePrefs(tag: String): InMemorySharedPreferences {
        return InMemorySharedPreferences().also { prefs ->
            prefs.edit().putString("locale_tag", tag).apply()
        }
    }

    private val filesDirsByPrefs = mutableMapOf<InMemorySharedPreferences, File>()

    private fun mockContext(
        snapshotPrefs: InMemorySharedPreferences,
        expectedName: String,
        localePrefs: InMemorySharedPreferences
    ): Context {
        val filesDir = filesDirsByPrefs.getOrPut(snapshotPrefs) {
            Files.createTempDirectory("home-catalog-snapshot-test").toFile().also { dir ->
                dir.deleteOnExit()
            }
        }
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                when (firstArg<String>()) {
                    expectedName -> snapshotPrefs
                    "app_locale" -> localePrefs
                    else -> throw IllegalArgumentException("Unexpected prefs ${firstArg<String>()}")
                }
            }
            every { this@mockk.filesDir } returns filesDir
        }
    }

    private class DiagnosticArtworkDecisionCache(
        private val diagnostics: ArtworkDecisionCacheSnapshotDiagnostics
    ) : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
        private val delegate = InMemoryArtworkDecisionCache()

        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = delegate.get(key)
        override fun put(decision: ArtworkDecision) = delegate.put(decision)
        override fun remove(key: ArtworkDecisionKey) = delegate.remove(key)
        override fun linkPreviewToCanonical(
            previewKey: ArtworkDecisionKey,
            canonicalKey: ArtworkDecisionKey
        ) = delegate.linkPreviewToCanonical(previewKey, canonicalKey)
        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? =
            delegate.getCanonicalForPreview(previewKey)

        override fun invalidateBySettingsHash(settingsHash: String) =
            delegate.invalidateBySettingsHash(settingsHash)

        override fun invalidateByCredentialHash(credentialHash: String) =
            delegate.invalidateByCredentialHash(credentialHash)

        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) =
            delegate.invalidateArtworkPolicy(settingsHashes, credentialHashes)

        override fun invalidatePremiumArtworkPolicy() = delegate.invalidatePremiumArtworkPolicy()
        override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics = diagnostics
    }

    private class NonAuthoritativeArtworkDecisionCache : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
        private val loadState = ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative(
            decisionCount = 0,
            droppedDecisionCount = 1,
            quarantinedDecisionCount = 0
        )

        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = null
        override fun loadState(): ArtworkDecisionStoreLoadState = loadState
        override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics =
            ArtworkDecisionCacheSnapshotDiagnostics(
                loaded = true,
                decisionCount = 0,
                linkCount = 0,
                storeFilePresent = true,
                storeFileReadable = true,
                storeFileBytes = 128L,
                lastLoadSuccess = false,
                lastLoadReason = "partial_load",
                lastLoadErrorClass = "JsonParseException",
                droppedDecisionCount = 1,
                loadStateName = "LoadedPartialNonAuthoritative",
                authoritative = false,
                quarantinedDecisionCount = 0
            )

        override fun put(decision: ArtworkDecision) = Unit
        override fun remove(key: ArtworkDecisionKey) = Unit
        override fun linkPreviewToCanonical(
            previewKey: ArtworkDecisionKey,
            canonicalKey: ArtworkDecisionKey
        ) = Unit
        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null
        override fun invalidateBySettingsHash(settingsHash: String) = Unit
        override fun invalidateByCredentialHash(credentialHash: String) = Unit
        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit
        override fun invalidatePremiumArtworkPolicy() = Unit
    }

    private class LookupFailedArtworkDecisionCache : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = null
        override fun lookup(
            key: ArtworkDecisionKey,
            requiredContext: com.nexio.tv.core.artwork.ArtworkDecisionAuthorityContext?
        ): ArtworkDecisionLookupResult =
            ArtworkDecisionLookupResult.LookupFailed(
                decisionKey = key,
                errorClass = "IOException",
                messageHash = "abc123"
            )

        override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics =
            ArtworkDecisionCacheSnapshotDiagnostics(
                loaded = false,
                decisionCount = 0,
                linkCount = 0,
                storeFilePresent = true,
                storeFileReadable = false,
                storeFileBytes = null,
                lastLoadSuccess = false,
                lastLoadReason = "load_failed",
                lastLoadErrorClass = "IOException",
                droppedDecisionCount = null,
                loadStateName = "FailedNonAuthoritative",
                authoritative = false
            )

        override fun put(decision: ArtworkDecision) = Unit
        override fun remove(key: ArtworkDecisionKey) = Unit
        override fun linkPreviewToCanonical(
            previewKey: ArtworkDecisionKey,
            canonicalKey: ArtworkDecisionKey
        ) = Unit
        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null
        override fun invalidateBySettingsHash(settingsHash: String) = Unit
        override fun invalidateByCredentialHash(credentialHash: String) = Unit
        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit
        override fun invalidatePremiumArtworkPolicy() = Unit
    }

    private class FakeArtworkReferenceIntegrityValidator(
        vararg results: Pair<String, ArtworkReferenceIntegrityResult>
    ) : ArtworkReferenceIntegrityValidator {
        private val resultsByRef = results.toMap()

        override fun validate(ref: String?): ArtworkReferenceIntegrityResult =
            resultsByRef[ref?.trim().orEmpty()] ?: ArtworkReferenceIntegrityResult.Invalid("unexpected_ref")
    }

}
