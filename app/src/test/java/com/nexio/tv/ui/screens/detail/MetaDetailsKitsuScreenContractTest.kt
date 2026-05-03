package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetaDetailsKitsuScreenContractTest {

    @Test
    fun `anime detail uses characters and related labels`() {
        val projectRoot = System.getProperty("user.dir")
            ?: error("Cannot determine project root")
        val screenFile = File(projectRoot, "app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt")
        assertTrue("MetaDetailsScreen.kt must exist", screenFile.exists())

        val source = screenFile.readText()
        assertTrue(source.contains("val strTabCast = if (isAnimeDetail) \"Characters\""))
        assertTrue(source.contains("val strTabRelated = if (isAnimeDetail) \"Related\""))
        assertTrue(source.contains("PeopleSectionTab.RELATED"))
    }

    @Test
    fun `anime detail suppresses ratings but can render Kitsu reviews tab`() {
        val projectRoot = System.getProperty("user.dir")
            ?: error("Cannot determine project root")
        val screenFile = File(projectRoot, "app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt")
        assertTrue("MetaDetailsScreen.kt must exist", screenFile.exists())

        val source = screenFile.readText()
        assertTrue(source.contains("val hasReviewsSection = isReviewsLoading || reviews.isNotEmpty() || !reviewsError.isNullOrBlank()"))
        assertTrue(source.contains("val hasRatingsSection = isTvShow && !isAnimeDetail"))
    }
}
