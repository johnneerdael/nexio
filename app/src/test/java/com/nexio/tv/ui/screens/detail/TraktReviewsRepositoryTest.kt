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
            traktAuthService = mockk<TraktAuthService>(relaxed = true)
        )
        val repository = TraktReviewsRepository(provider)

        val page = repository.fetchPage(
            pathId = "fight-club-1999",
            isShow = false,
            page = 1,
            limit = 8
        )

        assertEquals(listOf("profile:0:trakt:reviews:movie:fight-club-1999:page:1:limit:8"), runtime.keys)
        assertEquals(1, page?.reviews?.size)
        assertTrue(page?.hasMore == true)
        assertEquals("Cine Phile", page?.reviews?.firstOrNull()?.author)
    }
}
