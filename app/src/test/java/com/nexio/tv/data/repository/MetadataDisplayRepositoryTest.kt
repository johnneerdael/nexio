package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationFieldTrace
import com.nexio.tv.core.metadata.router.MetadataLocalizationRejectedCandidate
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.metadata.router.resolver.Confidence
import com.nexio.tv.core.metadata.router.resolver.RatingCandidate
import com.nexio.tv.core.metadata.router.resolver.TrailerAvailability
import com.nexio.tv.core.metadata.router.resolver.TrailerResolution
import com.nexio.tv.core.metadata.router.resolver.SourceRole as RatingSourceRole
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ResolvedDetailRatingDisplay
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDisplayRepositoryTest {

    @Test
    fun `resolveDetailDisplay maps router result into resolved detail document`() = runTest {
        val routerFacade = mockRouterFacade()
        val repository = MetadataDisplayRepository(routerFacade)
        val request = MetadataRequest(
            contentId = "tmdb:1399",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            language = "nl",
            depth = MetadataDepth.DETAIL_CORE
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_CORE,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "tmdb:1399",
                title = "Game of Thrones",
                overview = "Seven noble families fight for control of Westeros.",
                poster = null,
                backdrop = null,
                logo = null,
                rating = 9.2,
                runtimeMinutes = 57,
                genres = listOf("Drama", "Fantasy"),
                releaseDate = "2011-04-17",
                language = "en",
                castMembers = listOf(
                    MetaCastMember(name = "Emilia Clarke", character = "Daenerys Targaryen")
                ),
                remoteIds = mapOf(
                    "imdb" to setOf("tt0944947"),
                    "TVDB" to setOf("121361"),
                    "trakt" to setOf("1390"),
                    "simkl" to setOf("17485"),
                    "mal" to setOf(""),
                    "AniList" to setOf("999")
                ),
                fieldOwners = mapOf(
                    ResolvedField.TITLE to FieldOwner.PRIMARY,
                    ResolvedField.OVERVIEW to FieldOwner.PRIMARY
                ),
                ignoredOverwrites = emptyList(),
                localization = mapOf(
                    ResolvedField.TITLE to MetadataLocalizationFieldTrace(
                        field = ResolvedField.TITLE,
                        selectedProvider = MetadataPrimaryProvider.TVDB,
                        selectedLanguage = "en",
                        fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                        sourceApiShapeId = "tvdb.series.translation",
                        rejectedCandidates = listOf(
                            MetadataLocalizationRejectedCandidate(
                                provider = MetadataPrimaryProvider.TVDB,
                                language = "nl",
                                fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                                reason = "empty localized title"
                            )
                        )
                    )
                ),
                sourceRoles = mapOf(
                    ResolvedField.TITLE to SourceRole.PRIMARY,
                    ResolvedField.RATING to SourceRole.RATING
                ),
                sourceProviders = mapOf(
                    ResolvedField.TITLE to "TMDB",
                    ResolvedField.RATING to "TMDB_RATING"
                ),
                artwork = ArtworkBundle(
                    poster = artworkRef("resolved-poster", ArtworkType.POSTER),
                    logo = artworkRef("resolved-logo", ArtworkType.LOGO)
                )
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "Preview title",
                runtime = "60 min",
                ratingSource = TitleRatingSource.IMDB,
                artwork = ArtworkBundle(
                    poster = artworkRef("fallback-poster", ArtworkType.POSTER),
                    backdrop = artworkRef("fallback-backdrop", ArtworkType.BACKDROP),
                    thumbnail = artworkRef("fallback-thumbnail", ArtworkType.THUMBNAIL)
                )
            ),
            trace = emptyList()
        )

        val document = repository.resolveDetailDisplay(request)

        assertEquals("Game of Thrones", document.fields.title)
        assertEquals("Seven noble families fight for control of Westeros.", document.fields.overview)
        assertEquals(listOf("Drama", "Fantasy"), document.fields.genres)
        assertEquals("2011-04-17", document.fields.releaseDate)
        assertEquals(2011, document.fields.year)
        assertEquals("57 min", document.fields.runtimeText)
        assertEquals("TMDB", document.identity.canonicalProvider?.name)
        assertEquals("1399", document.identity.canonicalId)
        assertEquals("1399", document.identity.providerIds.tmdb)
        assertEquals("tt0944947", document.identity.providerIds.imdb)
        assertEquals("121361", document.identity.providerIds.tvdb)
        assertEquals("1390", document.identity.providerIds.trakt)
        assertEquals("17485", document.identity.providerIds.simkl)
        assertEquals("999", document.identity.providerIds.anilist)
        assertEquals(null, document.identity.providerIds.mal)
        assertEquals(9.2, document.rating?.value ?: 0.0, 0.0)
        assertEquals(TitleRatingSource.TMDB, document.rating?.source)
        assertEquals("resolved-poster-asset", document.artwork.poster?.assetKeyValue())
        assertEquals("fallback-backdrop-asset", document.artwork.backdrop?.assetKeyValue())
        assertEquals("resolved-logo-asset", document.artwork.logo?.assetKeyValue())
        assertEquals("fallback-thumbnail-asset", document.artwork.thumbnail?.assetKeyValue())
        assertTrue(document.trailer.fallbackTrailerYtIds.isEmpty())
        assertNotNull(document.people)
        assertEquals("Emilia Clarke", document.people?.cast?.single()?.name)
        assertTrue(document.people?.crew?.isEmpty() == true)
        assertEquals("nl", document.localization.requestedLanguage)
        assertEquals("en", document.localization.selectedLanguage)
        assertEquals(
            "TITLE fell back to en via TVDB (LANGUAGE_FALLBACK)",
            document.localization.fallbackReason
        )
        assertEquals(
            listOf("TITLE:TMDB:PRIMARY", "RATING:TMDB_RATING:RATING"),
            document.sourceTrace.map { "${it.field}:${it.selectedProvider}:${it.sourceRole}" }
        )
    }

    @Test
    fun `resolveDetailDisplay selected language comes from localization trace before advanced language`() = runTest {
        val routerFacade = mockRouterFacade()
        val repository = MetadataDisplayRepository(routerFacade)
        val request = MetadataRequest(
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            language = "nl",
            depth = MetadataDepth.DETAIL_CORE
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_CORE,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "tvdb:121361",
                title = "English TVDB title",
                overview = "English TVDB overview",
                poster = null,
                backdrop = null,
                logo = null,
                rating = null,
                runtimeMinutes = null,
                language = "ja",
                fieldOwners = mapOf(
                    ResolvedField.TITLE to FieldOwner.PRIMARY,
                    ResolvedField.OVERVIEW to FieldOwner.PRIMARY
                ),
                ignoredOverwrites = emptyList(),
                localization = mapOf(
                    ResolvedField.TITLE to MetadataLocalizationFieldTrace(
                        field = ResolvedField.TITLE,
                        selectedProvider = MetadataPrimaryProvider.TVDB,
                        selectedLanguage = "eng",
                        fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                        sourceApiShapeId = "tvdb.series.translation",
                        rejectedCandidates = listOf(
                            MetadataLocalizationRejectedCandidate(
                                provider = MetadataPrimaryProvider.TVDB,
                                language = "nld",
                                fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                                reason = "missing_or_placeholder"
                            )
                        )
                    )
                )
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview title"),
            trace = emptyList()
        )

        val document = repository.resolveDetailDisplay(request)

        assertEquals("eng", document.localization.selectedLanguage)
        assertEquals("ja", document.advanced.language)
        assertEquals(
            "TITLE fell back to eng via TVDB (LANGUAGE_FALLBACK)",
            document.localization.fallbackReason
        )
    }

    @Test
    fun `resolveDetailDisplay keeps selected language and fallback reason from same localization trace`() = runTest {
        val routerFacade = mockRouterFacade()
        val repository = MetadataDisplayRepository(routerFacade)
        val request = MetadataRequest(
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            language = "nl",
            depth = MetadataDepth.DETAIL_CORE
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_CORE,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "tvdb:121361",
                title = "Nederlandse TVDB titel",
                overview = "English TVDB overview",
                poster = null,
                backdrop = null,
                logo = null,
                rating = null,
                runtimeMinutes = null,
                fieldOwners = mapOf(
                    ResolvedField.TITLE to FieldOwner.PRIMARY,
                    ResolvedField.OVERVIEW to FieldOwner.PRIMARY
                ),
                ignoredOverwrites = emptyList(),
                localization = linkedMapOf(
                    ResolvedField.TITLE to MetadataLocalizationFieldTrace(
                        field = ResolvedField.TITLE,
                        selectedProvider = MetadataPrimaryProvider.TVDB,
                        selectedLanguage = "nld",
                        fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                        sourceApiShapeId = "tvdb.series.translation",
                        rejectedCandidates = emptyList()
                    ),
                    ResolvedField.OVERVIEW to MetadataLocalizationFieldTrace(
                        field = ResolvedField.OVERVIEW,
                        selectedProvider = MetadataPrimaryProvider.TVDB,
                        selectedLanguage = "eng",
                        fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                        sourceApiShapeId = "tvdb.series.translation",
                        rejectedCandidates = listOf(
                            MetadataLocalizationRejectedCandidate(
                                provider = MetadataPrimaryProvider.TVDB,
                                language = "nld",
                                fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                                reason = "missing_or_placeholder"
                            )
                        )
                    )
                )
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview title"),
            trace = emptyList()
        )

        val document = repository.resolveDetailDisplay(request)

        assertEquals("eng", document.localization.selectedLanguage)
        assertEquals(
            "OVERVIEW fell back to eng via TVDB (LANGUAGE_FALLBACK)",
            document.localization.fallbackReason
        )
    }

    @Test
    fun `resolveDetailDisplay reports visible title fallback when overview is localized`() = runTest {
        val routerFacade = mockRouterFacade()
        val repository = MetadataDisplayRepository(routerFacade)
        val request = MetadataRequest(
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            language = "nl",
            depth = MetadataDepth.DETAIL_CORE
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_CORE,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "tvdb:121361",
                title = "English TVDB title",
                overview = "Nederlandse TVDB overview",
                poster = null,
                backdrop = null,
                logo = null,
                rating = null,
                runtimeMinutes = null,
                fieldOwners = mapOf(
                    ResolvedField.TITLE to FieldOwner.PRIMARY,
                    ResolvedField.OVERVIEW to FieldOwner.PRIMARY
                ),
                ignoredOverwrites = emptyList(),
                localization = linkedMapOf(
                    ResolvedField.OVERVIEW to MetadataLocalizationFieldTrace(
                        field = ResolvedField.OVERVIEW,
                        selectedProvider = MetadataPrimaryProvider.TVDB,
                        selectedLanguage = "nld",
                        fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                        sourceApiShapeId = "tvdb.series.translation",
                        rejectedCandidates = emptyList()
                    ),
                    ResolvedField.TITLE to MetadataLocalizationFieldTrace(
                        field = ResolvedField.TITLE,
                        selectedProvider = MetadataPrimaryProvider.TVDB,
                        selectedLanguage = "eng",
                        fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                        sourceApiShapeId = "tvdb.series.translation",
                        rejectedCandidates = listOf(
                            MetadataLocalizationRejectedCandidate(
                                provider = MetadataPrimaryProvider.TVDB,
                                language = "nld",
                                fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                                reason = "missing_or_placeholder"
                            )
                        )
                    )
                )
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview title"),
            trace = emptyList()
        )

        val document = repository.resolveDetailDisplay(request)

        assertEquals("eng", document.localization.selectedLanguage)
        assertEquals(
            "TITLE fell back to eng via TVDB (LANGUAGE_FALLBACK)",
            document.localization.fallbackReason
        )
    }

    @Test
    fun `resolveDetailDisplay fills missing provider ids from route target ids`() = runTest {
        val routerFacade = mockRouterFacade()
        val repository = MetadataDisplayRepository(routerFacade)
        val request = MetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            language = "en",
            depth = MetadataDepth.DETAIL_FULL
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TVDB,
                parentId = "tt0944947",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                sourceContext = MetadataSourceContext(),
                language = "en",
                seasonNumber = null,
                targetIds = mapOf(
                    MetadataPrimaryProvider.TMDB to "tmdb:1399",
                    MetadataPrimaryProvider.TVDB to "tvdb:121361"
                ),
                trace = emptyList()
            ),
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_FULL,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "imdb:tt0944947",
                title = "Game of Thrones",
                overview = null,
                poster = null,
                backdrop = null,
                logo = null,
                rating = null,
                runtimeMinutes = null,
                remoteIds = mapOf(
                    "imdb" to setOf("tt0944947"),
                    "tmdb" to setOf("999")
                ),
                fieldOwners = emptyMap(),
                ignoredOverwrites = emptyList()
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview"),
            trace = emptyList()
        )

        val document = repository.resolveDetailDisplay(request)

        assertEquals("tt0944947", document.identity.providerIds.imdb)
        assertEquals("999", document.identity.providerIds.tmdb)
        assertEquals("121361", document.identity.providerIds.tvdb)
    }

    @Test
    fun `resolveDetailDisplay carries detail rating display state in resolved document`() = runTest {
        val routerFacade = mockRouterFacade()
        val ratingRepository = mockk<DetailRatingDisplayRepository>()
        val repository = MetadataDisplayRepository(
            metadataRouterFacade = routerFacade,
            detailRatingDisplayRepository = ratingRepository
        )
        val request = MetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "en",
            depth = MetadataDepth.DETAIL_FULL
        )
        val meta = minimalRatingsMeta("tt0944947")
        val ratings = ResolvedDetailRatingDisplay(
            titleRating = TitleRating(8.8, TitleRatingSource.IMDB),
            mdbListRatings = MDBListRatings(imdb = 8.8, trakt = 91.0),
            showMdbListImdb = true
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_FULL,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "imdb:tt0944947",
                title = "Game of Thrones",
                overview = null,
                poster = null,
                backdrop = null,
                logo = null,
                rating = 9.2,
                runtimeMinutes = null,
                remoteIds = mapOf("imdb" to setOf("tt0944947")),
                fieldOwners = emptyMap(),
                ignoredOverwrites = emptyList()
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview"),
            trace = emptyList()
        )
        coEvery {
            ratingRepository.resolve(
                meta = meta,
                fallbackItemId = "tt0944947",
                fallbackItemType = "series",
                providerIds = any(),
                episodesBySeason = emptyMap(),
                primaryProviderTitleRatingCandidate = ratingCandidate(9.2, RatingSourceRole.PRIMARY_PROVIDER, "IMDB"),
                previewFallbackTitleRatingCandidate = null
            )
        } returns ratings

        val document = repository.resolveDetailDisplay(
            request = request,
            ratingContext = DetailRatingDisplayContext(
                meta = meta,
                fallbackItemId = "tt0944947",
                fallbackItemType = "series",
                episodesBySeason = emptyMap()
            )
        )

        assertEquals(8.8, document.rating?.value ?: 0.0, 0.0)
        assertEquals(TitleRatingSource.IMDB, document.rating?.source)
        assertEquals(ratings, document.ratings)
        coVerify(exactly = 1) {
            ratingRepository.resolve(
                meta = meta,
                fallbackItemId = "tt0944947",
                fallbackItemType = "series",
                providerIds = any(),
                episodesBySeason = emptyMap(),
                primaryProviderTitleRatingCandidate = ratingCandidate(9.2, RatingSourceRole.PRIMARY_PROVIDER, "IMDB"),
                previewFallbackTitleRatingCandidate = null
            )
        }
    }

    @Test
    fun `resolveDetailDisplay projects resolved string artwork into detail artwork`() = runTest {
        val routerFacade = mockRouterFacade()
        val repository = MetadataDisplayRepository(routerFacade)
        val request = MetadataRequest(
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            language = "en",
            depth = MetadataDepth.DETAIL_FULL
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_FULL,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "tvdb:121361",
                title = "TVDB Title",
                overview = null,
                poster = "https://image.tvdb.test/poster.jpg",
                backdrop = "https://image.tvdb.test/backdrop.jpg",
                logo = "https://image.tvdb.test/logo.png",
                rating = null,
                runtimeMinutes = null,
                fieldOwners = emptyMap(),
                ignoredOverwrites = emptyList()
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview"),
            trace = emptyList()
        )

        val document = repository.resolveDetailDisplay(request)

        assertEquals("https://image.tvdb.test/poster.jpg", document.artwork.poster.toLegacyArtworkString())
        assertEquals("https://image.tvdb.test/backdrop.jpg", document.artwork.backdrop.toLegacyArtworkString())
        assertEquals("https://image.tvdb.test/logo.png", document.artwork.logo.toLegacyArtworkString())
    }

    @Test
    fun `resolveDetailDisplay derives rating context when preview context is unavailable`() = runTest {
        val routerFacade = mockRouterFacade()
        val ratingRepository = mockk<DetailRatingDisplayRepository>()
        val repository = MetadataDisplayRepository(
            metadataRouterFacade = routerFacade,
            detailRatingDisplayRepository = ratingRepository
        )
        val request = MetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "en",
            depth = MetadataDepth.DETAIL_FULL
        )
        val ratings = ResolvedDetailRatingDisplay(
            titleRating = TitleRating(8.8, TitleRatingSource.IMDB),
            mdbListRatings = MDBListRatings(imdb = 8.8),
            showMdbListImdb = true
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_FULL,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "imdb:tt0944947",
                title = "Game of Thrones",
                overview = "Seven noble families fight for control of Westeros.",
                poster = null,
                backdrop = null,
                logo = null,
                rating = 9.2,
                runtimeMinutes = 57,
                releaseDate = "2011-04-17",
                remoteIds = mapOf("imdb" to setOf("tt0944947")),
                fieldOwners = emptyMap(),
                ignoredOverwrites = emptyList()
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview"),
            trace = emptyList()
        )
        coEvery {
            ratingRepository.resolve(
                meta = match { it.id == "tt0944947" && it.name == "Game of Thrones" },
                fallbackItemId = "tt0944947",
                fallbackItemType = "series",
                providerIds = any(),
                episodesBySeason = emptyMap(),
                primaryProviderTitleRatingCandidate = ratingCandidate(9.2, RatingSourceRole.PRIMARY_PROVIDER, "IMDB"),
                previewFallbackTitleRatingCandidate = null
            )
        } returns ratings

        val document = repository.resolveDetailDisplay(request)

        assertEquals(ratings, document.ratings)
        assertEquals(8.8, document.rating?.value ?: 0.0, 0.0)
        coVerify(exactly = 1) {
            ratingRepository.resolve(
                meta = match { it.id == "tt0944947" && it.name == "Game of Thrones" },
                fallbackItemId = "tt0944947",
                fallbackItemType = "series",
                providerIds = any(),
                episodesBySeason = emptyMap(),
                primaryProviderTitleRatingCandidate = ratingCandidate(9.2, RatingSourceRole.PRIMARY_PROVIDER, "IMDB"),
                previewFallbackTitleRatingCandidate = null
            )
        }
    }

    @Test
    fun `resolveDetailDisplay keeps primary rating when optional rating display fails`() = runTest {
        val routerFacade = mockRouterFacade()
        val ratingRepository = mockk<DetailRatingDisplayRepository>()
        val repository = MetadataDisplayRepository(
            metadataRouterFacade = routerFacade,
            detailRatingDisplayRepository = ratingRepository
        )
        val request = MetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "en",
            depth = MetadataDepth.DETAIL_FULL
        )

        coEvery { routerFacade.resolveRequest(any()) } returns MetadataResolutionResult(
            route = null,
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_FULL,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "imdb:tt0944947",
                title = "Game of Thrones",
                overview = null,
                poster = null,
                backdrop = null,
                logo = null,
                rating = 9.2,
                runtimeMinutes = null,
                fieldOwners = emptyMap(),
                ignoredOverwrites = emptyList()
            ),
            displayMetadata = HomeDisplayMetadata(title = "Preview"),
            trace = emptyList()
        )
        coEvery {
            ratingRepository.resolve(any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("ratings unavailable")

        val document = repository.resolveDetailDisplay(request)

        assertEquals("Game of Thrones", document.fields.title)
        assertEquals(9.2, document.rating?.value ?: 0.0, 0.0)
        assertEquals(TitleRatingSource.IMDB, document.rating?.source)
        assertEquals(document.rating, document.ratings.titleRating)
    }

    private fun minimalRatingsMeta(id: String): Meta =
        Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = "series",
            name = "Game of Thrones",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )

    private fun ratingCandidate(
        value: Double,
        sourceRole: RatingSourceRole,
        sourceProvider: String
    ): RatingCandidate =
        RatingCandidate(
            value = value,
            sourceRole = sourceRole,
            sourceProvider = sourceProvider,
            confidence = Confidence.MEDIUM
        )

    private fun mockRouterFacade(): MetadataRouterFacade =
        mockk<MetadataRouterFacade>().also { routerFacade ->
            every { routerFacade.resolveTrailer(any()) } returns TrailerResolution(
                availability = TrailerAvailability(available = false, reason = "no_candidates"),
                candidates = emptyList(),
                selected = null,
                trace = emptyList()
            )
        }

    private fun artworkRef(key: String, type: ArtworkType): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("$key-decision"),
            assetKey = ArtworkAssetKey("$key-asset"),
            imageType = type,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty()
        )

    private fun ArtworkDisplayRef.assetKeyValue(): String? =
        (this as? ArtworkDisplayRef.RuntimeAsset)?.assetKey?.value
}
