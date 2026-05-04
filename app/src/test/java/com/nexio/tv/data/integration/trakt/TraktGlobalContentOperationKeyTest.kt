package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

class TraktGlobalContentOperationKeyTest {

    @Test
    fun trending_movies_operation_key_is_global_not_account_scoped() = runTest {
        val recorded = recordOperationKey { provider -> provider.fetchTrendingMovies(limit = 20) }
        assertGlobalShape(recorded, label = "trakt.trending.movies")
    }

    @Test
    fun trending_shows_operation_key_is_global_not_account_scoped() = runTest {
        val recorded = recordOperationKey { provider -> provider.fetchTrendingShows(limit = 20) }
        assertGlobalShape(recorded, label = "trakt.trending.shows")
    }

    @Test
    fun popular_movies_operation_key_is_global_not_account_scoped() = runTest {
        val recorded = recordOperationKey { provider -> provider.fetchPopularMovies(limit = 20) }
        assertGlobalShape(recorded, label = "trakt.popular.movies")
    }

    @Test
    fun popular_shows_operation_key_is_global_not_account_scoped() = runTest {
        val recorded = recordOperationKey { provider -> provider.fetchPopularShows(limit = 20) }
        assertGlobalShape(recorded, label = "trakt.popular.shows")
    }

    @Test
    fun calendar_shows_operation_key_is_global_not_account_scoped() = runTest {
        val recorded = recordOperationKey { provider ->
            provider.fetchCalendarShows(startDate = "2026-05-04", days = 7)
        }
        assertGlobalShape(recorded, label = "trakt.calendar.shows")
    }

    @Test
    fun recommendations_operation_key_is_global_not_account_scoped() = runTest {
        val recorded = recordOperationKey { provider ->
            provider.fetchRecommendations(type = "movies", limit = 20)
        }
        assertGlobalShape(recorded, label = "trakt.recommendations.movies")
    }

    @Test
    fun popular_lists_operation_key_is_global_not_account_scoped() = runTest {
        val recorded = recordOperationKey { provider -> provider.fetchPopularLists(page = 1, limit = 20) }
        assertGlobalShape(recorded, label = "trakt.popular.lists")
    }

    private suspend fun recordOperationKey(
        action: suspend (TraktIntegrationProvider) -> Unit
    ): String {
        val runtime = RecordingIntegrationRuntime<List<Any>>(successValue = emptyList())
        val session = TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = 1,
            credentialHash = "hash-p1"
        )
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = mockk(relaxed = true),
            traktAuthService = mockk<TraktAuthService> {
                coEvery { accountScopedSession() } returns session
                coEvery { accountScopedSession(any()) } returns session
            }
        )
        action(provider)
        return runtime.specs.firstOrNull()?.operationKey ?: error("no IntegrationSpec recorded")
    }

    private fun assertGlobalShape(operationKey: String, label: String) {
        assertFalse(
            "$label operationKey must not contain profile prefix; got: $operationKey",
            operationKey.contains("profile:")
        )
        assertFalse(
            "$label operationKey must not contain credential prefix; got: $operationKey",
            operationKey.contains("credential:")
        )
    }
}
