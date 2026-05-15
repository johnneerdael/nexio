package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvImage
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanartTvCandidateGeneratorTest {

    private val lookup = mockk<FanartTvLookup>(relaxed = true)
    private val capabilityResolver = com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver()
    private fun gen(availability: FanartTvAvailability) = FanartTvCandidateGenerator(
        availabilityProvider = { availability },
        idSelector = FanartTvIdSelector(),
        picker = FanartTvImagePicker(),
        lookup = lookup,
        capabilityResolver = capabilityResolver
    )

    private val ownerKey = ArtworkOwnerKey.CanonicalContent("movie:550")
    private val movieIds = ProviderIds(tmdb = "550")

    private fun settingsWith(
        poster: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
        logo: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
        backdrop: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT
    ) = ArtworkProviderSettings(
        selection = ArtworkProviderSelectionSettings(
            posterProvider = poster, logoProvider = logo, backdropProvider = backdrop
        )
    )

    @Test
    fun `disabled key emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Disabled("no_build_config_key")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `no type has FANART_TV selected emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith()
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `anime + FANART_TV selected emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "anime:1", MetadataMediaKind.ANIME, ProviderIds(tvdb = "1", tmdb = "1"),
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `missing usable id + FANART_TV selected emits zero, no lookup`() = runTest {
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:noid", MetadataMediaKind.MOVIE, ProviderIds(imdb = "tt0137523"),
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
        coVerify(exactly = 0) { lookup.fetch(any(), any()) }
    }

    @Test
    fun `success path emits candidates for non-null picker outputs`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                hdMovieLogo = listOf(FanartTvImage(id = "1", url = "logo.png", lang = "en", likes = "8")),
                movieBackground = listOf(FanartTvImage(id = "2", url = "back.jpg", lang = "", likes = "5")),
                moviePoster = listOf(FanartTvImage(id = "3", url = "poster.jpg", lang = "en", likes = "15"))
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(
                poster = ArtworkProviderChoiceKey.FANART_TV,
                logo = ArtworkProviderChoiceKey.FANART_TV,
                backdrop = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        coVerify(exactly = 1) { lookup.fetch(any(), any()) }
        assertEquals(3, out.size)
        assertEquals(
            setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            out.map { it.imageType }.toSet()
        )
        assertTrue(out.all { it.sourceRole == ArtworkSourceRole.PREMIUM })
        assertTrue(out.all {
            it.provider == ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV)
        })
    }

    @Test
    fun `partial picker outputs emit only present types`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                moviePoster = listOf(FanartTvImage(id = "1", url = "p.jpg", lang = "en", likes = "1"))
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(
                poster = ArtworkProviderChoiceKey.FANART_TV,
                logo = ArtworkProviderChoiceKey.FANART_TV,
                backdrop = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        assertEquals(1, out.size)
        assertEquals(ArtworkType.POSTER, out.single().imageType)
    }

    @Test
    fun `mixed selection only emits for FANART_TV-selected types`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Success(
            FanartTvDocument(
                hdMovieLogo = listOf(FanartTvImage(id = "1", url = "logo.png", lang = "en", likes = "8")),
                movieBackground = listOf(FanartTvImage(id = "2", url = "back.jpg", lang = "", likes = "5")),
                moviePoster = listOf(FanartTvImage(id = "3", url = "poster.jpg", lang = "en", likes = "15"))
            )
        )
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER, ArtworkType.LOGO, ArtworkType.BACKDROP),
            settings = settingsWith(
                poster = ArtworkProviderChoiceKey.FANART_TV,
                logo = ArtworkProviderChoiceKey.DEFAULT,
                backdrop = ArtworkProviderChoiceKey.FANART_TV
            )
        )
        coVerify(exactly = 1) { lookup.fetch(any(), any()) }
        assertEquals(setOf(ArtworkType.POSTER, ArtworkType.BACKDROP), out.map { it.imageType }.toSet())
    }

    @Test
    fun `404 emits zero candidates`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.NotFound
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `auth failure emits zero`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.AuthFailed
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `transient emits zero`() = runTest {
        coEvery { lookup.fetch(any(), any()) } returns FanartTvLookupResult.Transient
        val out = gen(FanartTvAvailability.Available("k")).generate(
            ownerKey, "movie:550", MetadataMediaKind.MOVIE, movieIds,
            requestedTypes = setOf(ArtworkType.POSTER),
            settings = settingsWith(poster = ArtworkProviderChoiceKey.FANART_TV)
        )
        assertTrue(out.isEmpty())
    }
}
