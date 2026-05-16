package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkSelectionResult
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataArtworkDecisionResolverFanartTvTest {
    @Test
    fun `resolveFields invokes generator once per ownerKey with the union of imageTypes`() = runTest {
        val generator = mockk<FanartTvCandidateGenerator>()
        coEvery {
            generator.generate(any(), any(), any(), any(), any(), any())
        } returns emptyList()

        // Use ArtworkSelectionResult to mock router selection — empty selection so
        // resolveFields returns no FieldValues; we only care that generator was called.
        val router = mockk<ArtworkRouter>()
        every { router.select(any(), any()) } returns ArtworkSelectionResult(
            selectedCandidateOrNull = null,
            rejectedCandidates = emptyList()
        )

        val decisionCache = mockk<com.nexio.tv.core.artwork.ArtworkDecisionCache>(relaxed = true)
        val remoteSourceStore = mockk<ArtworkRemoteSourceStore>(relaxed = true)
        val settingsSource = mockk<ArtworkProviderSettingsSource>()
        every { settingsSource.settings } returns MutableStateFlow(ArtworkProviderSettings())

        val resolver = MetadataArtworkDecisionResolver(
            artworkRouter = router,
            artworkDecisionCache = decisionCache,
            remoteSourceStore = remoteSourceStore,
            settingsSource = settingsSource,
            fanartGenerator = generator
        )

        val ownerKey = ArtworkOwnerKey.CanonicalContent("series:81189")
        val candidates = listOf(
            primary(ownerKey, ArtworkType.POSTER, "p.jpg"),
            primary(ownerKey, ArtworkType.BACKDROP, "b.jpg")
        )
        resolver.resolveFields(candidates)

        val typesSlot = slot<Set<ArtworkType>>()
        coVerify(exactly = 1) {
            generator.generate(
                ownerKey = ownerKey,
                canonicalContentId = "series:81189",
                mediaKind = MetadataMediaKind.SERIES,
                providerIds = any(),
                requestedTypes = capture(typesSlot),
                settings = any()
            )
        }
        assertEquals(setOf(ArtworkType.POSTER, ArtworkType.BACKDROP), typesSlot.captured)
    }

    private fun primary(ownerKey: ArtworkOwnerKey, type: ArtworkType, url: String): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ownerKey,
            canonicalContentId = "series:81189",
            providerIds = ProviderIds(tvdb = "81189"),
            mediaKind = MetadataMediaKind.SERIES,
            imageType = type,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
            sourceRole = ArtworkSourceRole.PRIMARY,
            source = ArtworkSource.RemoteUrl.of(SensitiveArtworkUrl.of(url), "h" + type.name),
            priority = 20,
            requiresRuntimeFetch = true,
            imageLanguage = "en"
        )
}
