package com.nexio.tv.data.repository

import android.content.Context
import com.nexio.tv.R
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.addon.AddonMetaIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.dto.MetaDto
import com.nexio.tv.data.remote.dto.MetaResponseDto
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaRepositoryImplTest {
    @Test
    fun `getMeta delegates to addon meta integration provider using direct meta scope and maps response`() = runTest {
        val context = mockk<Context>()
        val provider = mockk<AddonMetaIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val addonBaseUrl = "https://addon.example"
        val metaUrl = "https://addon.example/meta/movie/tt123.json"
        val directMetaAddonId = addonBaseUrl.trim().trimEnd('/')

        every { context.getString(R.string.episodes_episode) } returns "Episode"
        val localePrefs = InMemorySharedPreferences()
        every { context.getSharedPreferences("app_locale", Context.MODE_PRIVATE) } returns localePrefs
        AppLocaleResolver.setStoredLocaleTag(context, "en")
        coEvery { posterResolver.getActiveProvider() } returns null
        every { diskCacheStore.readMeta(any(), any(), any()) } returns null
        every { posterResolver.apply(any<Meta>(), null) } answers { firstArg() }
        coEvery {
            provider.getMeta(
                addonId = directMetaAddonId,
                metaUrl = metaUrl
            )
        } returns NetworkResult.Success(
            MetaResponseDto(
                meta = MetaDto(
                    id = "tt123",
                    type = "movie",
                    name = "The Example Movie",
                    poster = "https://images.example/poster.jpg"
                )
            )
        )

        val repository = MetaRepositoryImpl(
            context = context,
            addonMetaIntegrationProvider = provider,
            addonRepository = addonRepository,
            posterRatingsUrlResolver = posterResolver,
            metadataDiskCacheStore = diskCacheStore
        )

        val emissions = repository.getMeta(
            addonBaseUrl = "https://addon.example",
            type = "movie",
            id = "tt123",
            cacheOnDisk = true,
            writeToDisk = true,
            origin = "test"
        ).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions.first() is NetworkResult.Loading)
        val success = emissions.last() as NetworkResult.Success
        assertEquals("tt123", success.data.id)
        assertEquals(ContentType.MOVIE, success.data.type)
        assertEquals("The Example Movie", success.data.name)
        assertEquals("https://images.example/poster.jpg", success.data.poster)

        coVerify(exactly = 1) {
            provider.getMeta(
                addonId = directMetaAddonId,
                metaUrl = metaUrl
            )
        }
    }

    @Test
    fun `getMetaFromAllAddons delegates to addon meta integration provider using addon id`() = runTest {
        val context = mockk<Context>()
        val provider = mockk<AddonMetaIntegrationProvider>()
        val addonRepository = mockk<AddonRepository>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val addon = Addon(
            id = "community.addon",
            name = "Community Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE),
            resources = listOf(
                AddonResource(
                    name = "meta",
                    types = listOf("movie"),
                    idPrefixes = null
                )
            )
        )
        val metaUrl = "https://addon.example/meta/movie/tt123.json"

        every { context.getString(R.string.episodes_episode) } returns "Episode"
        val localePrefs = InMemorySharedPreferences()
        every { context.getSharedPreferences("app_locale", Context.MODE_PRIVATE) } returns localePrefs
        AppLocaleResolver.setStoredLocaleTag(context, "en")
        coEvery { posterResolver.getActiveProvider() } returns null
        every { diskCacheStore.readMeta(any(), any(), any()) } returns null
        every { posterResolver.apply(any<Meta>(), null) } answers { firstArg() }
        coEvery { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery {
            provider.getMeta(
                addonId = "community.addon",
                metaUrl = metaUrl
            )
        } returns NetworkResult.Success(
            MetaResponseDto(
                meta = MetaDto(
                    id = "tt123",
                    type = "movie",
                    name = "The Example Movie"
                )
            )
        )

        val repository = MetaRepositoryImpl(
            context = context,
            addonMetaIntegrationProvider = provider,
            addonRepository = addonRepository,
            posterRatingsUrlResolver = posterResolver,
            metadataDiskCacheStore = diskCacheStore
        )

        val emissions = repository.getMetaFromAllAddons(
            type = "movie",
            id = "tt123",
            cacheOnDisk = true,
            writeToDisk = false,
            origin = "test"
        ).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions.first() is NetworkResult.Loading)
        val success = emissions.last() as NetworkResult.Success
        assertEquals("tt123", success.data.id)
        assertEquals("The Example Movie", success.data.name)

        coVerify(exactly = 1) {
            provider.getMeta(
                addonId = "community.addon",
                metaUrl = metaUrl
            )
        }
    }

    @Test
    fun `meta disk aliases include resolved response type when request type differs`() {
        val meta = meta(type = ContentType.SERIES, rawType = "series")

        assertEquals(
            setOf("anime:kitsu:12", "series:kitsu:12"),
            buildMetaDiskAliasKeys(
                candidateType = "anime",
                candidateId = "kitsu:12",
                meta = meta
            )
        )
    }

    private fun meta(type: ContentType, rawType: String): Meta {
        return Meta(
            id = "kitsu:12",
            type = type,
            rawType = rawType,
            name = "One Piece",
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
    }
}
