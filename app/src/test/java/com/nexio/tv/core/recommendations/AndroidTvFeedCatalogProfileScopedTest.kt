package com.nexio.tv.core.recommendations

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * F-G-01 part 2 source-level pin: AndroidTvFeedCatalogService MUST NOT consume
 * `continueWatchingSnapshotService.observeSnapshot()` without a profile filter.
 * Use the typed profile-scoped API — `observeProfileSnapshot(profileId)` (preferred,
 * Task 6) or `observeContinueWatching(profileId)` (legacy name) — or apply an explicit
 * `.filter { it.profileId == activeProfileId }` on the snapshot flow.
 */
class AndroidTvFeedCatalogProfileScopedTest {

    @Test
    fun `AndroidTvFeedCatalogService cw subscription is profile-scoped`() {
        val src = File("app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt")
        require(src.exists()) { "Source not found at ${src.absolutePath}" }
        val text = src.readText()

        // Either: uses observeProfileSnapshot(profileId) or observeContinueWatching(profileId) directly
        val usesProfileScopedApi = Regex(
            "continueWatchingSnapshotService\\.(observeProfileSnapshot|observeContinueWatching)\\("
        ).containsMatchIn(text)
        // Or: keeps observeSnapshot but applies an explicit profileId filter
        val hasObserveSnapshot = Regex("continueWatchingSnapshotService\\.observeSnapshot\\(").containsMatchIn(text)
        val hasProfileFilter = Regex("\\.filter\\s*\\{[^}]*profileId").containsMatchIn(text)

        val isProfileScoped = usesProfileScopedApi || (!hasObserveSnapshot) || hasProfileFilter
        assertTrue(
            "F-G-01 part 2: AndroidTvFeedCatalogService must scope CW subscription to active profile " +
                "(observeProfileSnapshot/observeContinueWatching call, or .filter on profileId after observeSnapshot)",
            isProfileScoped
        )
    }

    @Test
    fun `AndroidTvFeedCatalogService uses explicit continue watching fallback policy`() {
        val src = File("app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvFeedCatalogService.kt")
        require(src.exists()) { "Source not found at ${src.absolutePath}" }
        val text = src.readText()

        assertTrue(text.contains("resolveActiveProfileContinueWatchingSnapshot()"))
        assertTrue(text.contains("withTimeoutOrNull(CONTINUE_WATCHING_SNAPSHOT_TIMEOUT_MS)"))
        assertTrue(text.contains("ContinueWatchingSnapshot()"))
        assertFalse(
            "AndroidTvFeedCatalogService must not resolve CW snapshots through a direct service assignment without fallback",
            text.contains("val continueWatchingSnapshot = continueWatchingSnapshotService")
        )
    }
}
