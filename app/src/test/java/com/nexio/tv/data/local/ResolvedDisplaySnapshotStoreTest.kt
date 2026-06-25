package com.nexio.tv.data.local

import com.google.gson.Gson
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PlaceholderType
import com.nexio.tv.core.artwork.RejectedArtworkCandidate
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolvedDisplaySnapshotStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun storeFor(profileId: Int = 1, lang: String = "en"): ResolvedDisplaySnapshotStore =
        ResolvedDisplaySnapshotStore.forTesting(
            rootDir = tempFolder.newFolder("resolved-display-v1"),
            activeProfileId = { profileId },
            currentLanguageTag = { lang },
        )

    private fun sampleItem(itemKey: String, title: String): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = itemKey.substringAfterLast(':'),
        parentId = itemKey.substringAfterLast(':'),
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = null,
        canonicalId = null,
        imdbId = null,
        stableIds = ProviderIds(),
        display = ResolvedDisplayFields(
            title = title, originalTitle = null, year = null, releaseDate = null,
            overview = null, genres = emptyList(), runtimeText = null
        ),
        artwork = ArtworkBundle(),
        rating = null,
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.PREVIEW_ONLY,
        sourceTrace = emptyList(),
        updatedAtMs = 0L,
        slots = null
    )

    @Test
    fun `round-trip empty map`() {
        val store = storeFor()
        store.write(emptyMap())
        val read = store.read()
        assertEquals(emptyMap<String, ResolvedDisplayItem>(), read)
    }

    @Test
    fun `round-trip single item`() {
        val store = storeFor()
        val item = sampleItem("movie:tt1234567", "Test Movie")
        store.write(mapOf(item.itemKey to item))
        val read = store.read()
        assertEquals(1, read.size)
        assertEquals("Test Movie", read[item.itemKey]?.display?.title)
    }

    @Test
    fun `round-trip preserves typed display authority fields`() {
        val store = storeFor(lang = "nl-NL")
        val runtimePoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("movie-tmdb-550-poster"),
            assetKey = ArtworkAssetKey("asset-movie-tmdb-550-poster"),
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace(
                selectedProvider = "FANART_TV",
                sourceRole = "PREMIUM",
                reason = "preferred_provider",
                rejectedCandidates = listOf(
                    RejectedArtworkCandidate(
                        provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                        sourceRole = ArtworkSourceRole.PRIMARY,
                        reason = "missing",
                        sourceHash = "tmdb:missing"
                    ),
                    RejectedArtworkCandidate(
                        provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                        sourceRole = ArtworkSourceRole.PREMIUM,
                        reason = "wrong-type",
                        sourceHash = "fanart:wrong-type"
                    )
                )
            ),
            displayHints = ArtworkDisplayHints(embedsRatingOverlay = true)
        )
        val item = sampleItem("movie:tmdb:550", "Fight Club").copy(
            contentId = "tmdb:550",
            parentId = "tmdb:550",
            canonicalProvider = "tmdb",
            canonicalId = "550",
            imdbId = "tt0137523",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523", simkl = "12345"),
            display = ResolvedDisplayFields(
                title = "Fight Club",
                originalTitle = "Fight Club",
                year = 1999,
                releaseDate = "1999-10-15",
                overview = "An insomniac office worker meets a soap maker.",
                genres = listOf("Drama", "Thriller"),
                runtimeText = "139m",
                tomatoesRating = 79.0
            ),
            artwork = ArtworkBundle(
                poster = runtimePoster,
                backdrop = ArtworkDisplayRef.LegacyString(
                    value = "https://image.tmdb.org/t/p/original/backdrop.jpg",
                    imageType = ArtworkType.BACKDROP,
                    trace = ArtworkTrace(selectedProvider = "TMDB", sourceRole = "PRIMARY")
                ),
                logo = ArtworkDisplayRef.Placeholder(
                    placeholderType = PlaceholderType.LOGO,
                    imageType = ArtworkType.LOGO,
                    trace = ArtworkTrace(reason = "missing_logo")
                )
            ),
            rating = TitleRating(8.8, TitleRatingSource.IMDB),
            trailer = TrailerDisplayState(
                fallbackTrailerYtIds = listOf("yt-a", "yt-b"),
                selectedPlaybackRef = TrailerPlaybackRef.ItemLookup(
                    title = "Fight Club",
                    year = "1999",
                    stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
                    type = "movie",
                    contentId = "tmdb:550",
                    fallbackYtIds = listOf("yt-a")
                ),
                availabilityReason = "item_lookup",
                surface = "home",
                resolverSource = "cache",
                lastResolvedAtMs = 1234L
            ),
            hydrationState = HydrationState.CANONICAL_READY,
            slots = ResolvedDisplayFieldSlots(
                title = slot("Fight Club", DisplaySourceRank.RESOLVED, "tmdb"),
                originalTitle = slot("Fight Club", DisplaySourceRank.RESOLVED, "tmdb"),
                overview = slot("An insomniac office worker meets a soap maker.", DisplaySourceRank.RESOLVED, "tmdb"),
                genres = slot(listOf("Drama", "Thriller"), DisplaySourceRank.RESOLVED, "tmdb"),
                releaseInfo = slot("1999", DisplaySourceRank.RESOLVED, "tmdb"),
                runtime = slot("139m", DisplaySourceRank.RESOLVED, "tmdb"),
                rating = slot(TitleRating(8.8, TitleRatingSource.IMDB), DisplaySourceRank.RESOLVED, "imdb"),
                poster = slot(runtimePoster, DisplaySourceRank.RESOLVED, "FANART_TV"),
                backdrop = slot(null, DisplaySourceRank.STALE_RESOLVED, "tmdb"),
                logo = slot(null, DisplaySourceRank.RESOLVED, "FANART_TV"),
                thumbnail = slot(null, DisplaySourceRank.EMPTY, null),
                posterProviderTag = slot("FANART_TV", DisplaySourceRank.RESOLVED, "FANART_TV")
            ),
            preferredArtworkProviders = mapOf(
                ArtworkType.POSTER to ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                ArtworkType.LOGO to ArtworkProviderId.AddonPreview
            ),
            displayLanguageTag = "nl-NL"
        )

        store.write(mapOf(item.itemKey to item))

        val restored = store.read()[item.itemKey]
        assertEquals("tt0137523", restored?.stableIds?.imdb)
        assertEquals("12345", restored?.stableIds?.simkl)
        assertEquals("nl-NL", restored?.displayLanguageTag)
        assertEquals(TitleRating(8.8, TitleRatingSource.IMDB), restored?.rating)
        assertEquals("FANART_TV", restored?.slots?.posterProviderTag?.value)
        assertEquals(DisplaySourceRank.RESOLVED, restored?.slots?.poster?.rank)
        assertEquals("yt-a", restored?.trailer?.fallbackTrailerYtIds?.firstOrNull())
        assertEquals("item_lookup", restored?.trailer?.availabilityReason)
        val trailerLookup = restored?.trailer?.selectedPlaybackRef as? TrailerPlaybackRef.ItemLookup
        assertEquals("Fight Club", trailerLookup?.title)
        assertEquals("tt0137523", trailerLookup?.stableIds?.imdb)
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            restored?.preferredArtworkProviders?.get(ArtworkType.POSTER)
        )
        val restoredPoster = restored?.artwork?.poster as? ArtworkDisplayRef.RuntimeAsset
        assertEquals("movie-tmdb-550-poster", restoredPoster?.decisionKey?.value)
        assertEquals("asset-movie-tmdb-550-poster", restoredPoster?.assetKey?.value)
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV), restoredPoster?.selectedProvider)
        assertEquals(true, restoredPoster?.displayHints?.embedsRatingOverlay)
        assertEquals("preferred_provider", restoredPoster?.trace?.reason)
        assertEquals(
            listOf("tmdb:missing", "fanart:wrong-type"),
            restoredPoster?.trace?.rejectedCandidates?.map { it.sourceHash }
        )
        val restoredBackdrop = restored?.artwork?.backdrop as? ArtworkDisplayRef.LegacyString
        assertEquals("https://image.tmdb.org/t/p/original/backdrop.jpg", restoredBackdrop?.value)
        val restoredLogo = restored?.artwork?.logo as? ArtworkDisplayRef.Placeholder
        assertEquals(PlaceholderType.LOGO, restoredLogo?.placeholderType)
    }

    @Test
    fun `read repairs decision-only runtime refs to durable asset refs`() {
        val decisionKey = ArtworkDecisionKey("movie-rpdb-tt0137523-poster")
        val repairedAssetKey = ArtworkAssetKey("asset-rpdb-poster-tt0137523")
        val decisionOnlyPoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = decisionKey,
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace(selectedProvider = "RPDB", sourceRole = "PREMIUM")
        )
        val store = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = tempFolder.newFolder("resolved-display-repair"),
            activeProfileId = { 1 },
            currentLanguageTag = { "en" },
            repairDecisionRefToAssetKey = { key ->
                if (key == decisionKey) repairedAssetKey else null
            }
        )
        val item = sampleItem("movie:imdb:tt0137523", "Fight Club").copy(
            artwork = ArtworkBundle(poster = decisionOnlyPoster),
            slots = ResolvedDisplayFieldSlots(
                title = slot("Fight Club", DisplaySourceRank.RESOLVED, "tmdb"),
                originalTitle = slot("Fight Club", DisplaySourceRank.RESOLVED, "tmdb"),
                overview = slot("An insomniac office worker meets a soap maker.", DisplaySourceRank.RESOLVED, "tmdb"),
                genres = slot(listOf("Drama", "Thriller"), DisplaySourceRank.RESOLVED, "tmdb"),
                releaseInfo = slot("1999", DisplaySourceRank.RESOLVED, "tmdb"),
                runtime = slot("139m", DisplaySourceRank.RESOLVED, "tmdb"),
                rating = slot(TitleRating(8.8, TitleRatingSource.IMDB), DisplaySourceRank.RESOLVED, "imdb"),
                poster = slot(decisionOnlyPoster, DisplaySourceRank.RESOLVED, "RPDB"),
                backdrop = slot(null, DisplaySourceRank.EMPTY, null),
                logo = slot(null, DisplaySourceRank.EMPTY, null),
                thumbnail = slot(null, DisplaySourceRank.EMPTY, null),
                posterProviderTag = slot("RPDB", DisplaySourceRank.RESOLVED, "RPDB")
            )
        )

        store.write(mapOf(item.itemKey to item))

        val restored = store.read()[item.itemKey]
        val restoredPoster = restored?.artwork?.poster as? ArtworkDisplayRef.RuntimeAsset
        val restoredSlotPoster = restored?.slots?.poster?.value as? ArtworkDisplayRef.RuntimeAsset
        assertEquals(repairedAssetKey, restoredPoster?.assetKey)
        assertEquals(repairedAssetKey, restoredSlotPoster?.assetKey)
        assertEquals(decisionKey, restoredPoster?.decisionKey)
        assertEquals(decisionKey, restoredSlotPoster?.decisionKey)
    }

    @Test
    fun `round-trip multiple items preserves keys and values`() {
        val store = storeFor()
        val items = (1..5).associate { i ->
            "movie:tt$i" to sampleItem("movie:tt$i", "Movie $i")
        }
        store.write(items)
        val read = store.read()
        assertEquals(5, read.size)
        items.forEach { (key, expected) ->
            assertEquals(expected.display.title, read[key]?.display?.title)
        }
    }

    @Test
    fun `read reusable current-language snapshot restores another profile cache`() {
        val root = tempFolder.newFolder("resolved-display-cross-profile")
        val profileOneStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 1 },
            currentLanguageTag = { "nl" }
        )
        val item = sampleItem("movie:tmdb:550", "Vechtclub").copy(displayLanguageTag = "nl")
        profileOneStore.write(mapOf(item.itemKey to item), profileId = 1)
        val profileTwoStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 2 },
            currentLanguageTag = { "nl" }
        )

        val read = profileTwoStore.readReusableCurrentLanguageSnapshot(profileId = 2)

        assertEquals("Vechtclub", read[item.itemKey]?.display?.title)
    }

    @Test
    fun `read reusable current-language snapshot rejects different-language cache`() {
        val root = tempFolder.newFolder("resolved-display-cross-language")
        val profileOneStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 1 },
            currentLanguageTag = { "en" }
        )
        val item = sampleItem("movie:tmdb:550", "Fight Club").copy(displayLanguageTag = "en")
        profileOneStore.write(mapOf(item.itemKey to item), profileId = 1)
        val profileTwoStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 2 },
            currentLanguageTag = { "nl" }
        )

        val read = profileTwoStore.readReusableCurrentLanguageSnapshot(profileId = 2)

        assertTrue(read.isEmpty())
    }

    @Test
    fun `read reusable artwork snapshot strips different-language metadata`() {
        val root = tempFolder.newFolder("resolved-display-artwork-only")
        val profileOneStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 1 },
            currentLanguageTag = { "en" }
        )
        val runtimePoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("movie-tmdb-550-poster"),
            assetKey = ArtworkAssetKey("asset-movie-tmdb-550-poster"),
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace(selectedProvider = "TMDB", sourceRole = "PRIMARY")
        )
        val item = sampleItem("movie:tmdb:550", "Fight Club").copy(
            contentId = "tmdb:550",
            parentId = "tmdb:550",
            canonicalProvider = "tmdb",
            canonicalId = "550",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            artwork = ArtworkBundle(poster = runtimePoster),
            slots = ResolvedDisplayFieldSlots(
                title = slot("Fight Club", DisplaySourceRank.RESOLVED, "tmdb"),
                originalTitle = slot("Fight Club", DisplaySourceRank.RESOLVED, "tmdb"),
                overview = slot("English overview", DisplaySourceRank.RESOLVED, "tmdb"),
                genres = slot(listOf("Drama"), DisplaySourceRank.RESOLVED, "tmdb"),
                releaseInfo = slot("1999", DisplaySourceRank.RESOLVED, "tmdb"),
                runtime = slot("139m", DisplaySourceRank.RESOLVED, "tmdb"),
                rating = slot(TitleRating(8.8, TitleRatingSource.IMDB), DisplaySourceRank.RESOLVED, "imdb"),
                poster = slot(runtimePoster, DisplaySourceRank.RESOLVED, "TMDB"),
                backdrop = slot(null, DisplaySourceRank.EMPTY, null),
                logo = slot(null, DisplaySourceRank.EMPTY, null),
                thumbnail = slot(null, DisplaySourceRank.EMPTY, null),
                posterProviderTag = slot("TMDB", DisplaySourceRank.RESOLVED, "TMDB")
            ),
            displayLanguageTag = "en",
            hydrationState = HydrationState.CANONICAL_READY
        )
        profileOneStore.write(mapOf(item.itemKey to item), profileId = 1)
        val profileTwoStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 2 },
            currentLanguageTag = { "nl" }
        )

        val read = profileTwoStore.readReusableArtworkSnapshot(profileId = 2)
        val restored = read[item.itemKey]

        assertEquals("nl", restored?.displayLanguageTag)
        assertNull(restored?.display?.title)
        assertNull(restored?.display?.overview)
        assertNull(restored?.rating)
        assertEquals(HydrationState.PREVIEW_ONLY, restored?.hydrationState)
        assertEquals(DisplaySourceRank.EMPTY, restored?.slots?.title?.rank)
        assertEquals(DisplaySourceRank.STALE_RESOLVED, restored?.slots?.poster?.rank)
        val restoredPoster = restored?.artwork?.poster as? ArtworkDisplayRef.RuntimeAsset
        assertEquals("asset-movie-tmdb-550-poster", restoredPoster?.assetKey?.value)
    }

    @Test
    fun `read reusable artwork snapshot drops placeholder-only items`() {
        val root = tempFolder.newFolder("resolved-display-artwork-placeholder")
        val profileOneStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 1 },
            currentLanguageTag = { "en" }
        )
        val placeholderPoster = ArtworkDisplayRef.Placeholder(
            placeholderType = PlaceholderType.POSTER,
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace(reason = "missing")
        )
        val item = sampleItem("movie:tmdb:550", "Fight Club").copy(
            artwork = ArtworkBundle(poster = placeholderPoster),
            slots = ResolvedDisplayFieldSlots(
                title = slot("Fight Club", DisplaySourceRank.RESOLVED, "tmdb"),
                originalTitle = slot(null, DisplaySourceRank.EMPTY, null),
                overview = slot(null, DisplaySourceRank.EMPTY, null),
                genres = slot(emptyList(), DisplaySourceRank.EMPTY, null),
                releaseInfo = slot(null, DisplaySourceRank.EMPTY, null),
                runtime = slot(null, DisplaySourceRank.EMPTY, null),
                rating = slot(null, DisplaySourceRank.EMPTY, null),
                poster = slot(placeholderPoster, DisplaySourceRank.PLACEHOLDER, "PLACEHOLDER"),
                backdrop = slot(null, DisplaySourceRank.EMPTY, null),
                logo = slot(null, DisplaySourceRank.EMPTY, null),
                thumbnail = slot(null, DisplaySourceRank.EMPTY, null),
                posterProviderTag = slot(null, DisplaySourceRank.EMPTY, null)
            ),
            displayLanguageTag = "en"
        )
        profileOneStore.write(mapOf(item.itemKey to item), profileId = 1)
        val profileTwoStore = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 2 },
            currentLanguageTag = { "nl" }
        )

        val read = profileTwoStore.readReusableArtworkSnapshot(profileId = 2)

        assertTrue(read.isEmpty())
    }

    @Test
    fun `read supports legacy schema v1 raw item files`() {
        val root = tempFolder.newFolder("legacy-resolved-display-v1")
        val store = ResolvedDisplaySnapshotStore.forTesting(
            rootDir = root,
            activeProfileId = { 1 },
            currentLanguageTag = { "en" }
        )
        val item = sampleItem("movie:tt1234567", "Legacy Movie")
        val legacyItemsJson = Gson().toJson(mapOf(item.itemKey to item))
        java.io.File(root, "p1_en.json").writeText(
            """{"schemaVersion":1,"items":$legacyItemsJson}""",
            Charsets.UTF_8
        )

        val read = store.read()

        assertEquals("Legacy Movie", read[item.itemKey]?.display?.title)
    }

    @Test
    fun `read returns empty map when file missing`() {
        val store = storeFor()
        val read = store.read()
        assertTrue(read.isEmpty())
    }

    private fun <T> slot(value: T?, rank: DisplaySourceRank, provider: String?): ResolvedSlot<T> =
        ResolvedSlot(
            value = value,
            rank = rank,
            provider = provider,
            role = null,
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = listOf("test")
        )
}
