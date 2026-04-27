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
}
