package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
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
                fieldOwners = mapOf(
                    ResolvedField.TITLE to FieldOwner.PRIMARY,
                    ResolvedField.OVERVIEW to FieldOwner.PRIMARY
                ),
                ignoredOverwrites = emptyList(),
                sourceRoles = mapOf(
                    ResolvedField.TITLE to SourceRole.PRIMARY
                ),
                sourceProviders = mapOf(
                    ResolvedField.TITLE to "TMDB"
                ),
                artwork = ArtworkBundle()
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "Preview title",
                runtime = "60 min",
                artwork = ArtworkBundle()
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
        assertEquals(9.2, document.rating?.value ?: 0.0, 0.0)
        assertEquals(TitleRatingSource.IMDB, document.rating?.source)
        assertTrue(document.trailer.fallbackTrailerYtIds.isEmpty())
        assertNotNull(document.people)
        assertEquals("Emilia Clarke", document.people?.cast?.single()?.name)
        assertTrue(document.people?.crew?.isEmpty() == true)
        assertEquals("nl", document.localization.requestedLanguage)
        assertEquals("en", document.localization.selectedLanguage)
        assertEquals(
            listOf("TITLE:TMDB:PRIMARY"),
            document.sourceTrace.map { "${it.field}:${it.selectedProvider}:${it.sourceRole}" }
        )
    }
}
