package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.integration.tmdb.TmdbExternalIdLookupProvider
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterFacadeTest {
    @Test
    fun `preview requests bypass route and provider plan`() = runTest {
        val addonMetadata = HomeDisplayMetadata(title = "Addon title")
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(addonMetadata = addonMetadata),
                depth = MetadataDepth.PREVIEW
            )
        )

        assertNull(result.route)
        assertNull(result.plan)
        assertEquals(MetadataDepth.PREVIEW, result.resolverSchedule.depth)
        assertSame(addonMetadata, result.displayMetadata)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `metadata facade executes provider plan and never calls provider metadata router`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(title = "Addon title")
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, result.route?.provider)
        assertEquals(MetadataDepth.DETAIL_CORE, result.plan?.depth)
        assertTrue(adapter.calls > 0)
        assertEquals("Runtime title", result.resolvedDocument.title)
        assertEquals("Runtime title", result.displayMetadata.title)
    }

    @Test
    fun `metadata facade maps canonical genres and release date into display metadata`() = runTest {
        val adapter = GenreReleaseMetadataProviderAdapter(MetadataPrimaryProvider.TMDB)

        val result = facade(adapter).resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        title = "Preview title",
                        genres = listOf("Preview"),
                        releaseInfo = "1999"
                    )
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(listOf("Canonical Genre"), result.resolvedDocument.genres)
        assertEquals("1999-10-15", result.resolvedDocument.releaseDate)
        assertEquals(listOf("Canonical Genre"), result.displayMetadata.genres)
        assertEquals("1999-10-15", result.displayMetadata.releaseInfo)
    }

    @Test
    fun `metadata facade carries typed preview artwork into resolved display metadata`() = runTest {
        val posterRef = artworkRef("previewPosterDecision", "previewPosterAsset", ArtworkType.POSTER)
        val result = facade(RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        title = "Preview title",
                        poster = "rawPreviewPoster",
                        artwork = ArtworkBundle(poster = posterRef)
                    ),
                    previewSourceRole = SourceRole.ADDON_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.TVDB.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(posterRef, result.resolvedDocument.artwork.poster)
        assertEquals("nexio-artwork://asset/previewPosterAsset", result.resolvedDocument.poster)
        assertEquals(posterRef, result.displayMetadata.artwork?.poster)
        assertEquals("nexio-artwork://asset/previewPosterAsset", result.displayMetadata.displayPoster)
    }

    @Test
    fun `metadata facade does not let fallback typed preview artwork override primary raw poster`() = runTest {
        val fallbackPosterRef = artworkRef("fallbackPosterDecision", "fallbackPosterAsset", ArtworkType.POSTER)
        val result = facade(PrimaryPosterMetadataProviderAdapter(MetadataPrimaryProvider.TMDB)).resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        artwork = ArtworkBundle(poster = fallbackPosterRef)
                    ),
                    previewSourceRole = SourceRole.RAIL_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.TRAKT.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals("primaryPoster", result.resolvedDocument.poster)
        assertEquals("primaryPoster", result.displayMetadata.poster)
        assertEquals("primaryPoster", result.displayMetadata.displayPoster)
        assertNull(result.displayMetadata.artwork?.poster)
    }

    @Test
    fun `resolved RPDB premium poster projects poster provider tag`() = runTest {
        val result = facade(
            RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TMDB),
            PremiumPosterMetadataProviderAdapter(
                provider = MetadataPrimaryProvider.RPDB,
                apiShapeId = PosterApiShapes.RPDB_POSTER_TEMPLATE,
                poster = "https://rpdb.example/poster.jpg"
            )
        ).resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        poster = "rawPreviewPoster",
                        posterProviderTag = "tmdb"
                    ),
                    previewSourceRole = SourceRole.RAIL_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.TMDB.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals("https://rpdb.example/poster.jpg", result.displayMetadata.poster)
        assertEquals("rpdb", result.displayMetadata.posterProviderTag)
    }

    @Test
    fun `preview poster preserves fallback premium poster provider tag when no replacement wins`() = runTest {
        val result = facade(RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TMDB)).resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        poster = "premiumFallbackPoster",
                        posterProviderTag = "rpdb"
                    ),
                    previewSourceRole = SourceRole.RAIL_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.RPDB.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals("premiumFallbackPoster", result.displayMetadata.poster)
        assertEquals(SourceRole.RAIL_PREVIEW, result.resolvedDocument.sourceRoles[ResolvedField.POSTER])
        assertEquals("rpdb", result.displayMetadata.posterProviderTag)
    }

    @Test
    fun `primary raw poster clears stale fallback premium poster provider tag`() = runTest {
        val result = facade(PrimaryPosterMetadataProviderAdapter(MetadataPrimaryProvider.TMDB)).resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        poster = "premiumFallbackPoster",
                        posterProviderTag = "rpdb"
                    ),
                    previewSourceRole = SourceRole.RAIL_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.RPDB.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals("primaryPoster", result.displayMetadata.poster)
        assertNull(result.displayMetadata.posterProviderTag)
    }

    @Test
    fun `rail preview source context participates without filling missing primary text`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)
        val sink = RecordingTraceSink()

        val result = facade(
            adapter,
            fieldResolver = FieldResolver(
                traceEvents = TraceMetadataEvents(sink, sessionId = { "metadata-router-facade-test" })
            )
        ).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        title = "Rail title",
                        description = "Rail overview"
                    ),
                    previewSourceRole = SourceRole.RAIL_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.TRAKT.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals("Runtime title", result.resolvedDocument.title)
        assertNull(result.resolvedDocument.overview)
        assertEquals(SourceRole.PRIMARY, result.resolvedDocument.sourceRoles[ResolvedField.TITLE])
        assertFalse(result.resolvedDocument.sourceRoles.containsKey(ResolvedField.OVERVIEW))
        assertEquals(
            IgnoredFieldOverwrite(
                field = ResolvedField.TITLE,
                existingOwner = FieldOwner.PRIMARY,
                attemptedOwner = FieldOwner.PRIMARY,
                attemptedValue = "Rail title"
            ),
            result.resolvedDocument.ignoredOverwrites.single()
        )

        val titlePayload = selectedTracePayload(sink, ResolvedField.TITLE)
        assertEquals("PRIMARY", titlePayload["sourceRole"])
        assertEquals("primary canonical field replaces rail preview", titlePayload["ownershipRule"])
        @Suppress("UNCHECKED_CAST")
        val rejectedCandidates = titlePayload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(
            mapOf(
                "provider" to "TRAKT",
                "sourceProvider" to "TRAKT",
                "sourceRole" to "RAIL_PREVIEW",
                "reason" to "primary canonical field available"
            ),
            rejectedCandidates.single()
        )
    }

    @Test
    fun `addon preview source context participates without filling missing primary text`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)
        val sink = RecordingTraceSink()

        val result = facade(
            adapter,
            fieldResolver = FieldResolver(
                traceEvents = TraceMetadataEvents(sink, sessionId = { "metadata-router-facade-test" })
            )
        ).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonId = "cinemeta",
                    addonMetadata = HomeDisplayMetadata(
                        title = "Addon title",
                        description = "Addon overview"
                    )
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals("Runtime title", result.resolvedDocument.title)
        assertNull(result.resolvedDocument.overview)
        assertFalse(result.resolvedDocument.sourceRoles.containsKey(ResolvedField.OVERVIEW))

        val titlePayload = selectedTracePayload(sink, ResolvedField.TITLE)
        assertEquals("PRIMARY", titlePayload["sourceRole"])
        assertEquals("primary canonical field replaces addon preview", titlePayload["ownershipRule"])
        @Suppress("UNCHECKED_CAST")
        val rejectedCandidates = titlePayload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(
            mapOf(
                "provider" to "TVDB",
                "sourceProvider" to "cinemeta",
                "sourceRole" to "ADDON_PREVIEW",
                "reason" to "primary canonical field available"
            ),
            rejectedCandidates.single()
        )
    }

    @Test
    fun `successful TVDB route suppresses preview text when primary localized text is missing`() = runTest {
        val adapter = EmptyTextMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonId = "cinemeta",
                    addonMetadata = HomeDisplayMetadata(
                        title = "Addon title",
                        description = "Addon overview",
                        poster = "Addon poster"
                    ),
                    previewSourceRole = SourceRole.ADDON_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.TMDB.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, result.route?.provider)
        assertNull(result.resolvedDocument.title)
        assertNull(result.resolvedDocument.overview)
        assertEquals("Addon poster", result.resolvedDocument.poster)
        assertFalse(result.resolvedDocument.sourceRoles.containsKey(ResolvedField.TITLE))
        assertFalse(result.resolvedDocument.sourceRoles.containsKey(ResolvedField.OVERVIEW))
        assertEquals(SourceRole.ADDON_PREVIEW, result.resolvedDocument.sourceRoles[ResolvedField.POSTER])
    }

    @Test
    fun `route request records authority without executing provider plan`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val route = facade(adapter).routeRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals("tvdb:123", route.parentId)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `provider plan steps are executed via integration runtime adapters`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        facade(adapter).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(listOf("tvdb.series.extended"), adapter.apiShapeIds)
    }

    @Test
    fun `raw IMDB movie addon hydration executes TMDB plan with provider native target`() = runTest {
        val lookup = FakeTmdbExternalIdLookup(tmdbForImdb = mapOf("tt12042730" to 687163))
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TMDB)

        val result = facade(
            adapter,
            tmdbExternalIdLookup = lookup
        ).resolveRequest(
            MetadataRequest(
                contentId = "tt12042730",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    previewSourceRole = SourceRole.ADDON_PREVIEW,
                    previewStableIds = ProviderIds(imdb = "tt12042730")
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        val executedRoute = adapter.executedRoutes.single()
        assertEquals(MetadataPrimaryProvider.TMDB, result.route?.provider)
        assertEquals(listOf("tmdb.movie.core"), adapter.apiShapeIds)
        assertEquals("tmdb:687163", executedRoute.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals("tt12042730", executedRoute.targetIds[MetadataPrimaryProvider.IMDB])
        assertTrue(
            "provider execution must not receive raw IMDB id as TMDB-native target",
            executedRoute.targetIds[MetadataPrimaryProvider.TMDB] != "tt12042730"
        )
        assertEquals(false, executedRoute.targetIdRequiresIdentityResolution)
        assertEquals(listOf("findTmdb:tt12042730:movie"), lookup.calls)
    }

    @Test
    fun `missing plan step adapter mapping fails test`() = runTest {
        val error = try {
            facade().resolveRequest(
                MetadataRequest(
                    contentId = "tvdb:123",
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(),
                    depth = MetadataDepth.DETAIL_CORE
                )
            )
            null
        } catch (exception: MetadataRouteFailure.MissingPlanStepAdapter) {
            exception
        }

        assertTrue(error?.message?.contains("tvdb.series.extended") == true)
    }

    @Test
    fun `provider-native conflict fails before provider plan execution when identity is unresolved`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val error = try {
            facade(adapter).resolveRequest(
                MetadataRequest(
                    contentId = "tmdb:1399",
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(),
                    depth = MetadataDepth.DETAIL_CORE
                )
            )
            null
        } catch (exception: MetadataRouteFailure.IdentityResolutionFailed) {
            exception
        }

        assertTrue("Expected unresolved identity route to be rejected", error != null)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `facade tv enrichment bridge uses resolved document output`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).fetchTvEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:81189",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.DETAIL_CORE
            ),
            tvRequest = TvMetadataRequest(
                contentId = "tvdb:81189",
                contentType = ContentType.SERIES
            )
        )

        assertEquals(TvProvider.TVDB, result.provider)
        assertEquals("Runtime title", result.value?.localizedTitle)
        assertTrue(adapter.calls > 0)
    }

    @Test
    fun `facade season episode bridge returns provider plan episode metadata`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).fetchTvSeasonEpisodes(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                seasonNumber = 1,
                depth = MetadataDepth.SEASON
            ),
            contentId = "tvdb:1399",
            fallbackContentId = null,
            seasonNumber = 1
        )

        assertEquals(TvProvider.TVDB, result.provider)
        assertEquals(listOf(1), result.value?.map { it.episodeNumber })
        assertEquals("2024-01-01", result.value?.single()?.airDate)
        assertTrue(adapter.apiShapeIds.contains("tvdb.series.episodes.language"))
    }

    @Test
    fun `episode enrichment retries fallback id when primary route returns no episodes`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(
            provider = MetadataPrimaryProvider.TVDB,
            emptyEpisodeParentIds = setOf("addon-series-id")
        )

        val result = facade(adapter).fetchTvEpisodeEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "addon-series-id",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                seasonNumber = 1,
                depth = MetadataDepth.SEASON
            ),
            tvRequest = TvMetadataRequest(
                contentId = "addon-series-id",
                fallbackContentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                seasonNumbers = listOf(1)
            )
        )

        assertEquals(TvProvider.TVDB, result.provider)
        assertEquals("Runtime episode", result.value?.get(1 to 1)?.title)
        assertEquals(
            listOf("addon-series-id", "tvdb:1399"),
            adapter.executedRoutes
                .filter { route -> route.seasonNumber == 1 }
                .map { route -> route.parentId }
                .distinct()
        )
    }

    @Test
    fun `episode enrichment does not resolve fallback id when primary route succeeds`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)
        val identityLookup = RecordingIdentityLookup(tmdbToTvdb = mapOf("999" to "1399"))

        val result = facade(
            adapter,
            identityLookup = identityLookup
        ).fetchTvEpisodeEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                seasonNumber = 1,
                depth = MetadataDepth.SEASON
            ),
            tvRequest = TvMetadataRequest(
                contentId = "tvdb:1399",
                fallbackContentId = "tmdb:999",
                contentType = ContentType.SERIES,
                seasonNumbers = listOf(1)
            )
        )

        assertEquals("Runtime episode", result.value?.get(1 to 1)?.title)
        assertEquals(listOf("tvdb:1399"), adapter.executedRoutes.map { route -> route.parentId }.distinct())
        assertTrue(identityLookup.calls.isEmpty())
    }

    @Test
    fun `top posters premium episode thumbnail wins over primary thumbnail with valid entitlement`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val result = facade(
            RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB),
            posterResolver = posterResolver(topPostersThumbnailSettings(), cache)
        ).fetchTvEpisodeEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                seasonNumber = 1,
                depth = MetadataDepth.SEASON
            ),
            tvRequest = TvMetadataRequest(
                contentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                seasonNumbers = listOf(1)
            )
        )

        val episode = result.value?.get(1 to 1)
        val artwork = episode?.thumbnailArtwork as? ArtworkDisplayRef.RuntimeAsset
        assertNotNull(artwork)
        artwork!!
        assertEquals("primaryThumbnail", episode.thumbnail)
        assertEquals("TOP_POSTERS", artwork.selectedProvider?.key)
        assertEquals(ArtworkSourceRole.PREMIUM, artwork.sourceRole)
        assertEquals("premium_artwork_provider_precedence", artwork.trace.rejectedCandidates.single().reason)

        val decision = cache.get(artwork.decisionKey)
        val template = decision?.selectedCandidate?.providerTemplate
        assertEquals("TOP_POSTERS", template?.provider?.key)
        assertEquals(ArtworkType.THUMBNAIL, template?.imageType)
        assertEquals("tvdb", template?.idType)
        assertEquals("1399", template?.mediaId)
        assertFalse("mediaId must not carry SxEy episode path", template?.mediaId.orEmpty().contains("S1E1"))
        assertEquals("1", template?.pathParams?.get("season"))
        assertEquals("1", template?.pathParams?.get("episode"))
        assertEquals("small", template?.pathParams?.get("badgeSize"))
        assertEquals("top-right", template?.pathParams?.get("badgePosition"))
        assertEquals("false", template?.pathParams?.get("blur"))
    }

    @Test
    fun `top posters episode thumbnail missing entitlement falls back to primary with rejection trace`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val result = facade(
            RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB),
            posterResolver = posterResolver(topPostersThumbnailSettings(entitlement = null), cache)
        ).fetchTvEpisodeEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                seasonNumber = 1,
                depth = MetadataDepth.SEASON
            ),
            tvRequest = TvMetadataRequest(
                contentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                seasonNumbers = listOf(1)
            )
        )

        val episode = result.value?.get(1 to 1)
        val artwork = episode?.thumbnailArtwork as? ArtworkDisplayRef.RuntimeAsset
        assertNotNull(artwork)
        artwork!!
        assertEquals("primaryThumbnail", episode.thumbnail)
        assertEquals("TVDB", artwork.selectedProvider?.key)
        assertEquals(ArtworkSourceRole.PRIMARY, artwork.sourceRole)
        assertEquals("topposters_entitlement_missing", artwork.trace.rejectedCandidates.single().reason)
        assertEquals("TVDB", cache.get(artwork.decisionKey)?.selectedCandidate?.provider?.key)
    }

    @Test
    fun `top posters episode thumbnail missing supported id falls back to primary with rejection trace`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val result = facade(
            RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB),
            posterResolver = posterResolver(topPostersThumbnailSettings(), cache)
        ).fetchTvEpisodeEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "addon-series-id",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                seasonNumber = 1,
                depth = MetadataDepth.SEASON
            ),
            tvRequest = TvMetadataRequest(
                contentId = "addon-series-id",
                contentType = ContentType.SERIES,
                seasonNumbers = listOf(1)
            )
        )

        val episode = result.value?.get(1 to 1)
        val artwork = episode?.thumbnailArtwork as? ArtworkDisplayRef.RuntimeAsset
        assertNotNull(artwork)
        artwork!!
        assertEquals("primaryThumbnail", episode.thumbnail)
        assertEquals("TVDB", artwork.selectedProvider?.key)
        assertEquals(ArtworkSourceRole.PRIMARY, artwork.sourceRole)
        assertEquals("missing_supported_provider_id", artwork.trace.rejectedCandidates.single().reason)
    }

    private fun facade(
        vararg adapters: MetadataProviderAdapter,
        identityLookup: MetadataIdentityResolver.Lookup = object : MetadataIdentityResolver.Lookup {
            override suspend fun tmdbToTvdb(tmdbId: String): String? = null
            override suspend fun tvdbToTmdb(tvdbId: String): String? = null
        },
        tmdbExternalIdLookup: TmdbExternalIdLookupProvider? = null,
        fieldResolver: FieldResolver = FieldResolver(),
        posterResolver: PosterRatingsUrlResolver? = null
    ): MetadataRouterFacade =
        MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore(),
                tmdbExternalIdLookup = tmdbExternalIdLookup
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = MetadataIdentityResolver(identityLookup),
            providerPlanRunner = ProviderPlanRunner(adapters.toSet()),
            fieldResolver = fieldResolver,
            posterRatingsUrlResolver = posterResolver
        )

    private fun posterResolver(
        settings: ArtworkProviderSettings,
        cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache()
    ): PosterRatingsUrlResolver {
        val dataStore = mockk<PosterRatingsSettingsDataStore>()
        every { dataStore.settings } returns flowOf(settings)
        return PosterRatingsUrlResolver(dataStore, cache)
    }

    private fun topPostersThumbnailSettings(
        entitlement: TopPostersEntitlementSnapshot? = premiumThumbnailEntitlement()
    ): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            ),
            topPostersEntitlement = entitlement
        )

    private fun premiumThumbnailEntitlement(): TopPostersEntitlementSnapshot =
        TopPostersEntitlementSnapshot(
            valid = true,
            isActive = true,
            tier = 1,
            tierName = "Premium",
            episodeThumbnails = true,
            verifiedAtMs = 1_000L,
            expiresAtMs = System.currentTimeMillis() + 86_400_000L
        )

    private fun selectedTracePayload(
        sink: RecordingTraceSink,
        field: ResolvedField
    ): Map<*, *> {
        val event = sink.events
            .first { it.eventType == "metadata.field_selected" && (it.payload as Map<*, *>)["field"] == field.name }
        return event.payload as Map<*, *>
    }

    private fun artworkRef(
        decisionKey: String,
        assetKey: String,
        imageType: ArtworkType
    ): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey(decisionKey),
            assetKey = ArtworkAssetKey(assetKey),
            imageType = imageType,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty()
        )

    private class RecordingMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider,
        private val emptyEpisodeParentIds: Set<String> = emptySet()
    ) : MetadataProviderAdapter {
        var calls = 0
        val apiShapeIds = mutableListOf<String>()
        val executedRoutes = mutableListOf<MetadataRoute>()

        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            calls += 1
            apiShapeIds += step.apiShapeId
            executedRoutes += route
            val episodeMetadata = if (step.role == ProviderPlanRole.SEASON && route.parentId !in emptyEpisodeParentIds) {
                val seasonNumber = route.seasonNumber ?: 1
                mapOf(
                    (seasonNumber to 1) to TvEpisodeMetadata(
                        seasonNumber = seasonNumber,
                        episodeNumber = 1,
                        title = "Runtime episode",
                        thumbnail = "primaryThumbnail",
                        airDate = "2024-01-01"
                    )
                )
            } else {
                emptyMap()
            }
            return ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = route.provider,
                    fields = mapOf(
                        ResolvedField.TITLE to FieldValue("Runtime title", FieldOwner.PRIMARY)
                    )
                ),
                episodeMetadata = episodeMetadata
            )
        }
    }

    private class EmptyTextMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = route.provider,
                    fields = emptyMap()
                )
            )
    }

    private class GenreReleaseMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = route.provider,
                    fields = mapOf(
                        ResolvedField.TITLE to FieldValue("Runtime title", FieldOwner.PRIMARY),
                        ResolvedField.GENRES to FieldValue(listOf("Canonical Genre"), FieldOwner.PRIMARY),
                        ResolvedField.RELEASE_DATE to FieldValue("1999-10-15", FieldOwner.PRIMARY)
                    )
                )
            )
    }

    private class PrimaryPosterMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = route.provider,
                    fields = mapOf(
                        ResolvedField.TITLE to FieldValue("Runtime title", FieldOwner.PRIMARY),
                        ResolvedField.POSTER to FieldValue("primaryPoster", FieldOwner.PRIMARY)
                    )
                )
            )
    }

    private class PremiumPosterMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider,
        private val apiShapeId: String,
        private val poster: String
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean =
            step.apiShapeId == apiShapeId

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    fields = mapOf(
                        ResolvedField.POSTER to FieldValue(
                            value = poster,
                            owner = FieldOwner.ARTWORK,
                            sourceRole = SourceRole.ARTWORK
                        )
                    ),
                    sourceProvider = provider.name,
                    sourceRole = SourceRole.ARTWORK
                )
            )
    }

    private class FakeTmdbExternalIdLookup(
        private val tmdbForImdb: Map<String, Int> = emptyMap()
    ) : TmdbExternalIdLookupProvider {
        val calls = mutableListOf<String>()

        override suspend fun findTmdbIdByImdbId(imdbId: String, mediaType: String): Int? {
            calls += "findTmdb:$imdbId:$mediaType"
            return tmdbForImdb[imdbId]
        }

        override suspend fun findImdbIdByTmdbId(tmdbId: Int, mediaType: String): String? {
            calls += "findImdb:$tmdbId:$mediaType"
            return null
        }
    }

    private class RecordingIdentityLookup(
        private val tmdbToTvdb: Map<String, String> = emptyMap(),
        private val tvdbToTmdb: Map<String, String> = emptyMap()
    ) : MetadataIdentityResolver.Lookup {
        val calls = mutableListOf<String>()

        override suspend fun tmdbToTvdb(tmdbId: String): String? {
            calls += "tmdbToTvdb:$tmdbId"
            return tmdbToTvdb[tmdbId]
        }

        override suspend fun tvdbToTmdb(tvdbId: String): String? {
            calls += "tvdbToTmdb:$tvdbId"
            return tvdbToTmdb[tvdbId]
        }
    }
}
