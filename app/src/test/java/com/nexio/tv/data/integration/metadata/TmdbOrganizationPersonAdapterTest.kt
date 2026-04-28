package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.domain.model.PersonDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbOrganizationPersonAdapterTest {

    private val samplePerson = PersonDetail(
        tmdbId = 287,
        name = "Brad Pitt",
        biography = null,
        birthday = null,
        deathday = null,
        placeOfBirth = null,
        profilePhoto = null,
        knownFor = null,
        movieCredits = emptyList(),
        tvCredits = emptyList()
    )

    @Test
    fun `supports declares all 6 person and organization shapes only`() {
        val adapter = TmdbOrganizationPersonAdapter(mockk(relaxed = true))
        assertTrue(adapter.supports(step(TmdbApiShapes.PERSON_DETAIL)))
        assertTrue(adapter.supports(step(TmdbApiShapes.PERSON_COMBINED_CREDITS)))
        assertTrue(adapter.supports(step(TmdbApiShapes.PERSON_FIND_BY_NAME)))
        assertTrue(adapter.supports(step(TmdbApiShapes.COMPANY_DETAIL)))
        assertTrue(adapter.supports(step(TmdbApiShapes.NETWORK_DETAIL)))
        assertTrue(adapter.supports(step(TmdbApiShapes.COMPANY_FIND_BY_NAME)))
        assertFalse(adapter.supports(step(TmdbApiShapes.MOVIE_CORE)))
        assertFalse(adapter.supports(step(TmdbApiShapes.TV_CORE)))
        assertFalse(adapter.supports(step(TmdbApiShapes.MOVIE_REVIEWS)))
    }

    @Test
    fun `PERSON_DETAIL emits CAST candidate via fetchPersonDetail with preferCrewCredits=false`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchPersonDetail(287, preferCrewCredits = false) } returns samplePerson
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = personRoute(),
            step = step(TmdbApiShapes.PERSON_DETAIL)
        )

        val candidate = result.candidate
        assertNotNull(candidate)
        assertEquals(MetadataPrimaryProvider.TMDB, candidate!!.provider)
        assertEquals(ResolverType.ORGANIZATION_PERSON, candidate.resolverType)
        assertEquals(samplePerson, candidate.fields[ResolvedField.CAST]?.value)
        assertNull(candidate.fields[ResolvedField.CREW])
        coVerify(exactly = 1) { repo.fetchPersonDetail(287, preferCrewCredits = false) }
    }

    @Test
    fun `PERSON_COMBINED_CREDITS emits CREW candidate with preferCrewCredits=true`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchPersonDetail(287, preferCrewCredits = true) } returns samplePerson
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = personRoute(),
            step = step(TmdbApiShapes.PERSON_COMBINED_CREDITS)
        )

        val candidate = result.candidate
        assertNotNull(candidate)
        assertEquals(samplePerson, candidate!!.fields[ResolvedField.CREW]?.value)
        assertNull(candidate.fields[ResolvedField.CAST])
        coVerify(exactly = 1) { repo.fetchPersonDetail(287, preferCrewCredits = true) }
    }

    @Test
    fun `PERSON_FIND_BY_NAME resolves id via repository then emits CAST`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.findPersonIdByExactName("Brad Pitt") } returns 287
        coEvery { repo.fetchPersonDetail(287, preferCrewCredits = false) } returns samplePerson
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = nameRoute(parentId = "tmdb:Brad Pitt"),
            step = step(TmdbApiShapes.PERSON_FIND_BY_NAME)
        )

        assertNotNull(result.candidate?.fields?.get(ResolvedField.CAST))
        coVerify(exactly = 1) { repo.findPersonIdByExactName("Brad Pitt") }
    }

    @Test
    fun `PERSON_FIND_BY_NAME returns empty candidate when name is unknown`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.findPersonIdByExactName("Nobody") } returns null
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = nameRoute(parentId = "Nobody"),
            step = step(TmdbApiShapes.PERSON_FIND_BY_NAME)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `COMPANY_DETAIL emits ORGANIZATION_LIST candidate carrying tmdb id`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = orgRoute(targetIds = mapOf(MetadataPrimaryProvider.TMDB to "420")),
            step = step(TmdbApiShapes.COMPANY_DETAIL)
        )

        val candidate = result.candidate
        assertNotNull(candidate)
        assertEquals(ResolverType.ORGANIZATION_PERSON, candidate!!.resolverType)
        @Suppress("UNCHECKED_CAST")
        val orgList = candidate.fields[ResolvedField.ORGANIZATION_LIST]?.value as List<Int>
        assertEquals(listOf(420), orgList)
    }

    @Test
    fun `NETWORK_DETAIL emits ORGANIZATION_LIST candidate carrying tmdb id`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = orgRoute(targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:213")),
            step = step(TmdbApiShapes.NETWORK_DETAIL)
        )

        @Suppress("UNCHECKED_CAST")
        val orgList = result.candidate?.fields?.get(ResolvedField.ORGANIZATION_LIST)?.value as List<Int>
        assertEquals(listOf(213), orgList)
    }

    @Test
    fun `COMPANY_FIND_BY_NAME resolves id via repository and emits ORGANIZATION_LIST`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.findCompanyIdByExactName("A24") } returns 41077
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = nameRoute(parentId = "tmdb:A24"),
            step = step(TmdbApiShapes.COMPANY_FIND_BY_NAME)
        )

        @Suppress("UNCHECKED_CAST")
        val orgList = result.candidate?.fields?.get(ResolvedField.ORGANIZATION_LIST)?.value as List<Int>
        assertEquals(listOf(41077), orgList)
    }

    @Test
    fun `missing tmdb id short circuits PERSON_DETAIL to empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = personRoute(targetIds = emptyMap()),
            step = step(TmdbApiShapes.PERSON_DETAIL)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `null PersonDetail from repo yields empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>()
        coEvery { repo.fetchPersonDetail(287, preferCrewCredits = false) } returns null
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = personRoute(),
            step = step(TmdbApiShapes.PERSON_DETAIL)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `unsupported shape yields empty candidate`() = runTest {
        val repo = mockk<MetadataSecondaryRepository>(relaxed = true)
        val adapter = TmdbOrganizationPersonAdapter(repo)

        val result = adapter.execute(
            route = personRoute(),
            step = step(TmdbApiShapes.MOVIE_VIDEOS)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    private fun step(shape: String) = ProviderPlanStep(
        apiShapeId = shape,
        provider = MetadataPrimaryProvider.TMDB,
        role = ProviderPlanRole.SECONDARY,
        required = false
    )

    private fun personRoute(
        targetIds: Map<MetadataPrimaryProvider, String> = mapOf(MetadataPrimaryProvider.TMDB to "287")
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = "tmdb:287",
        mediaKind = MetadataMediaKind.UNKNOWN,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = targetIds,
        trace = emptyList()
    )

    private fun orgRoute(
        targetIds: Map<MetadataPrimaryProvider, String>
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = "tmdb:org",
        mediaKind = MetadataMediaKind.UNKNOWN,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = targetIds,
        trace = emptyList()
    )

    private fun nameRoute(parentId: String) = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = parentId,
        mediaKind = MetadataMediaKind.UNKNOWN,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = emptyMap(),
        trace = emptyList()
    )
}
