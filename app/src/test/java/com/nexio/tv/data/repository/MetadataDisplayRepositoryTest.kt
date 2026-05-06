package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.TitleRatingSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDisplayRepositoryTest {

    @Test
    fun `resolveDetailDisplay maps router result into resolved detail document`() = runTest {
        val routerFacade = mockk<MetadataRouterFacade>()
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
                sourceRoles = mapOf(
                    ResolvedField.TITLE to SourceRole.PRIMARY,
                    ResolvedField.RATING to SourceRole.RATING
                ),
                sourceProviders = mapOf(
                    ResolvedField.TITLE to "TMDB",
                    ResolvedField.RATING to "TMDB"
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
            listOf("TITLE:TMDB:PRIMARY", "RATING:TMDB:RATING"),
            document.sourceTrace.map { "${it.field}:${it.selectedProvider}:${it.sourceRole}" }
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
