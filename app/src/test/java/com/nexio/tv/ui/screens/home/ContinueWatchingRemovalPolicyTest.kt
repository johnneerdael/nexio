package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingRemovalPolicyTest {
    @Test
    fun `in-progress series episode clear uses show-level progress clearing`() {
        assertTrue(
            shouldClearShowProgressForContinueWatchingRemoval(
                contentType = "series",
                season = 3,
                episode = 12,
                isNextUp = false
            )
        )
    }

    @Test
    fun `movie clear keeps item-level progress removal`() {
        assertFalse(
            shouldClearShowProgressForContinueWatchingRemoval(
                contentType = "movie",
                season = null,
                episode = null,
                isNextUp = false
            )
        )
    }

    @Test
    fun `next-up clear remains show-level even without explicit content type`() {
        assertTrue(
            shouldClearShowProgressForContinueWatchingRemoval(
                contentType = null,
                season = 1,
                episode = 2,
                isNextUp = true
            )
        )
    }
}
