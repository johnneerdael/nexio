package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.tvdb.TvdbAdvancedMetadataMapper
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbCharacterRecord
import com.nexio.tv.data.remote.api.TvdbCompanyExtendedRecord
import com.nexio.tv.data.remote.api.TvdbCompanyRecord
import com.nexio.tv.data.remote.api.TvdbGenreRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbMetadataProviderAdapterAdvancedFieldsTest {

    @Test
    fun `series extended emits cast companies networks and genres candidates`() = runTest {
        val provider = mockk<TvdbIntegrationProvider>()
        coEvery {
            provider.fetchSeriesExtendedCached(tvdbId = 121361, localizationPolicyVersion = any())
        } returns TvdbSeriesExtendedRecord(
            id = 121361,
            name = "Extended Title",
            characters = listOf(
                TvdbCharacterRecord(
                    name = "Joel Miller",
                    personName = "Pedro Pascal",
                    peopleId = 42,
                    sort = 1
                )
            ),
            companies = listOf(TvdbCompanyExtendedRecord(name = "Naughty Dog Productions")),
            originalNetwork = TvdbCompanyRecord(name = "HBO"),
            genres = listOf(TvdbGenreRecord(name = "Drama"))
        )
        coEvery {
            provider.fetchSeriesTranslationWithTrace(any(), any(), any(), any())
        } returns LocalizedPayloadFetch(
            value = TvdbTranslationRecord(name = "Localized Title", overview = "Localized overview"),
            trace = MetadataLocalizationPayloadTrace(
                provider = MetadataPrimaryProvider.TVDB,
                apiShapeId = TvdbApiShapes.SERIES_TRANSLATION,
                language = "eng",
                fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                cacheKey = "test",
                cacheDecision = "HIT",
                executedNetwork = false,
                policyVersion = LocalizationPolicy.CURRENT_VERSION
            )
        )
        val artworkDecisionResolver = mockk<MetadataArtworkDecisionResolver>()
        coEvery { artworkDecisionResolver.resolveFields(any()) } returns emptyMap()
        val adapter = TvdbMetadataProviderAdapter(
            integrationProvider = provider,
            traceEvents = TraceMetadataEvents(NoopRuntimeTraceSink) { null },
            artworkCandidateMapper = TvdbArtworkCandidateMapper(),
            artworkDecisionResolver = artworkDecisionResolver,
            advancedMetadataMapper = TvdbAdvancedMetadataMapper()
        )

        val result = adapter.execute(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TVDB,
                parentId = "tvdb:121361",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:121361"),
                trace = emptyList()
            ),
            step = ProviderPlanStep(
                apiShapeId = TvdbApiShapes.SERIES_EXTENDED,
                provider = MetadataPrimaryProvider.TVDB,
                role = ProviderPlanRole.PRIMARY_CORE,
                required = true
            )
        )

        val fields = result.candidate?.fields.orEmpty()
        val cast = fields[ResolvedField.CAST]?.value as List<*>
        val organizations = fields[ResolvedField.ORGANIZATION_LIST]?.value as List<*>
        val genres = fields[ResolvedField.GENRES]?.value as List<*>

        val castMember = cast.single() as MetaCastMember
        assertEquals("Pedro Pascal", castMember.name)
        assertEquals("Joel Miller", castMember.character)
        assertTrue(organizations.filterIsInstance<MetaCompany>().any { it.name == "Naughty Dog Productions" })
        assertTrue(organizations.filterIsInstance<MetaCompany>().any { it.name == "HBO" })
        assertEquals(listOf("Drama"), genres)
    }
}
