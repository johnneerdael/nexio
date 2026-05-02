package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.data.integration.trakt.TraktCommentsPage
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktCommentItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktCommentUserDto
import com.nexio.tv.data.remote.dto.trakt.TraktCommentUserStatsDto
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TraktReviewsRepository
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktReviewsRepositoryTest {
    @Test
    fun `trakt reviews repository uses runtime and caches pages by endpoint plus page`() = runTest {
        val runtime = RecordingIntegrationRuntime(
            successValue = TraktCommentsPage(
                items = listOf(
                    TraktCommentItemDto(
                        id = 77L,
                        comment = "Still one of the best.",
                        review = true,
                        spoiler = false,
                        user = TraktCommentUserDto(username = "cinephile", name = "Cine Phile"),
                        userStats = TraktCommentUserStatsDto(rating = 9.0)
                    )
                ),
                hasMore = true
            )
        )
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = mockk<TraktApi>(),
            traktAuthService = mockk<TraktAuthService> {
                val session = TrackingAuthSession(
                    provider = TrackingProvider.TRAKT,
                    profileId = 1,
                    credentialHash = "trakt-test-1"
                )
                every { currentAuthSession() } returns session
                coEvery { accountScopedSession() } returns session
            }
        )
        val repository = TraktReviewsRepository(provider)

        val page = repository.fetchPage(
            pathId = "fight-club-1999",
            isShow = false,
            page = 1,
            limit = 8
        )

        assertEquals(
            listOf("profile:1:provider:TRAKT:credential:trakt-test-1:trakt:reviews:movie:fight-club-1999:page:1:limit:8"),
            runtime.keys
        )
        assertEquals(1, page?.reviews?.size)
        assertTrue(page?.hasMore == true)
        assertEquals("Cine Phile", page?.reviews?.firstOrNull()?.author)
    }

    @Test
    fun `trakt reviews repository normalizes cached raw comment objects`() = runTest {
        @Suppress("UNCHECKED_CAST")
        val cachedItems = listOf(
            mapOf(
                "id" to 88.0,
                "comment" to "Cached review body.",
                "review" to true,
                "spoiler" to false,
                "user" to mapOf(
                    "username" to "cached-user",
                    "name" to "Cached User"
                ),
                "user_stats" to mapOf("rating" to 8.0)
            )
        ) as List<TraktCommentItemDto>
        val runtime = RecordingIntegrationRuntime(
            successValue = TraktCommentsPage(
                items = cachedItems,
                hasMore = false
            )
        )
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = mockk<TraktApi>(),
            traktAuthService = mockk<TraktAuthService> {
                val session = TrackingAuthSession(
                    provider = TrackingProvider.TRAKT,
                    profileId = 1,
                    credentialHash = "trakt-test-1"
                )
                every { currentAuthSession() } returns session
                coEvery { accountScopedSession() } returns session
            }
        )
        val repository = TraktReviewsRepository(provider)

        val page = repository.fetchPage(
            pathId = "fight-club-1999",
            isShow = false,
            page = 1,
            limit = 8
        )

        assertEquals(1, page?.reviews?.size)
        assertEquals("Cached User", page?.reviews?.firstOrNull()?.author)
        assertEquals(8.0, page?.reviews?.firstOrNull()?.rating)
    }
}
