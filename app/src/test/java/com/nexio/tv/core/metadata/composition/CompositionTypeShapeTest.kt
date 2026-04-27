package com.nexio.tv.core.metadata.composition

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.memberProperties

class CompositionTypeShapeTest {
    @Test
    fun `GlobalMetadataDocument does not declare profile overlay fields`() {
        val forbidden = setOf(
            "watched", "progress", "listMembership", "scrobbleState",
            "userRating", "continueWatching", "profileId", "overlay"
        )
        val declared = GlobalMetadataDocument::class.memberProperties.map { it.name }.toSet()
        val violations = declared.intersect(forbidden)
        assertTrue("GlobalMetadataDocument may not declare $violations", violations.isEmpty())
    }

    @Test
    fun `ProfileMetadataOverlay declares all expected profile-owned fields`() {
        val expected = setOf(
            "profileId", "watched", "progress", "listMembership",
            "scrobbleState", "userRating", "continueWatching"
        )
        val declared = ProfileMetadataOverlay::class.memberProperties.map { it.name }.toSet()
        assertTrue("ProfileMetadataOverlay missing fields: ${expected - declared}", declared.containsAll(expected))
    }

    @Test
    fun `ProfileResolvedDisplayDocument bundles profileId plus global plus overlay`() {
        val declared = ProfileResolvedDisplayDocument::class.memberProperties.map { it.name }.toSet()
        assertTrue(declared.containsAll(setOf("profileId", "global", "overlay")))
    }
}
