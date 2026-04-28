package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.tvdb.TvdbPersonService
import com.nexio.tv.domain.model.PersonDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbOrganizationPersonAdapterTest {

    private val samplePerson = PersonDetail(
        tmdbId = 0,
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
    fun `supports filters to PERSON_EXTENDED only`() {
        val adapter = TvdbOrganizationPersonAdapter(mockk(relaxed = true))
        assertTrue(adapter.supports(step(TvdbApiShapes.PERSON_EXTENDED)))
        assertFalse(adapter.supports(step(TvdbApiShapes.SERIES_BASE)))
        assertFalse(adapter.supports(step(TvdbApiShapes.SERIES_EXTENDED)))
        assertFalse(adapter.supports(step(TvdbApiShapes.SEARCH)))
    }

    @Test
    fun `PERSON_EXTENDED emits CAST candidate with PersonDetail from service`() = runTest {
        val service = mockk<TvdbPersonService>()
        coEvery { service.fetchPersonDetail(287) } returns samplePerson
        val adapter = TvdbOrganizationPersonAdapter(service)

        val result = adapter.execute(
            route = personRoute(parentId = "tvdb:person:287"),
            step = step(TvdbApiShapes.PERSON_EXTENDED)
        )

        val candidate = result.candidate
        assertNotNull(candidate)
        assertEquals(MetadataPrimaryProvider.TVDB, candidate!!.provider)
        assertEquals(ResolverType.ORGANIZATION_PERSON, candidate.resolverType)
        assertEquals(samplePerson, candidate.fields[ResolvedField.CAST]?.value)
        coVerify(exactly = 1) { service.fetchPersonDetail(287) }
    }

    @Test
    fun `parentId without colon is treated as raw integer id`() = runTest {
        val service = mockk<TvdbPersonService>()
        coEvery { service.fetchPersonDetail(42) } returns samplePerson
        val adapter = TvdbOrganizationPersonAdapter(service)

        val result = adapter.execute(
            route = personRoute(parentId = "42"),
            step = step(TvdbApiShapes.PERSON_EXTENDED)
        )
        assertNotNull(result.candidate?.fields?.get(ResolvedField.CAST))
        coVerify(exactly = 1) { service.fetchPersonDetail(42) }
    }

    @Test
    fun `unparseable parentId yields empty candidate without calling service`() = runTest {
        val service = mockk<TvdbPersonService>(relaxed = true)
        val adapter = TvdbOrganizationPersonAdapter(service)

        val result = adapter.execute(
            route = personRoute(parentId = "tvdb:person:not-an-int"),
            step = step(TvdbApiShapes.PERSON_EXTENDED)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
        coVerify(exactly = 0) { service.fetchPersonDetail(any()) }
    }

    @Test
    fun `null PersonDetail from service yields empty candidate`() = runTest {
        val service = mockk<TvdbPersonService>()
        coEvery { service.fetchPersonDetail(287) } returns null
        val adapter = TvdbOrganizationPersonAdapter(service)

        val result = adapter.execute(
            route = personRoute(parentId = "tvdb:person:287"),
            step = step(TvdbApiShapes.PERSON_EXTENDED)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
    }

    @Test
    fun `unsupported shape yields empty candidate without calling service`() = runTest {
        val service = mockk<TvdbPersonService>(relaxed = true)
        val adapter = TvdbOrganizationPersonAdapter(service)

        val result = adapter.execute(
            route = personRoute(parentId = "tvdb:person:287"),
            step = step(TvdbApiShapes.SERIES_BASE)
        )
        assertTrue(result.candidate?.fields.isNullOrEmpty())
        coVerify(exactly = 0) { service.fetchPersonDetail(any()) }
    }

    private fun step(shape: String) = ProviderPlanStep(
        apiShapeId = shape,
        provider = MetadataPrimaryProvider.TVDB,
        role = ProviderPlanRole.SECONDARY,
        required = false
    )

    private fun personRoute(parentId: String) = MetadataRoute(
        provider = MetadataPrimaryProvider.TVDB,
        parentId = parentId,
        mediaKind = MetadataMediaKind.UNKNOWN,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        language = null,
        targetIds = emptyMap(),
        trace = emptyList()
    )
}
