package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressProfileScopeArchitectureTest {
    @Test
    fun `WatchProgressPreferences must not default to active profile for store selection`() {
        val source = File("app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt").readText()

        assertFalse(
            "WatchProgressPreferences must not select storage from ProfileManager.activeProfileId",
            source.contains("activeProfileId")
        )
        assertFalse(
            "WatchProgressPreferences.store must not accept any active-profile default argument",
            Regex("""private\s+fun\s+store\s*\([^)]*=\s*[^)]*activeProfile""").containsMatchIn(source)
        )
        assertFalse(
            "WatchProgressPreferences write paths must not call store() without explicit profile/session",
            Regex("""store\s*\(\s*\)\s*\.\s*edit""").containsMatchIn(source)
        )
        assertFalse(
            "WatchProgressPreferences write paths must not call store(profileManager.activeProfileId.value)",
            Regex("""store\s*\(\s*profileManager\s*\.\s*activeProfileId\s*\.\s*value\s*\)\s*\.\s*edit""")
                .containsMatchIn(source)
        )
        assertFalse(
            "WatchProgressPreferences write paths must not select storage from any active profile/session API",
            Regex("""store\s*\([^)]*activeProfile(?:Id|Session)?[^)]*\)\s*\.\s*edit""")
                .containsMatchIn(source)
        )
        assertFalse(
            "WatchProgressPreferences write paths must not assign activeProfileSession then store(session.profileId)",
            Regex(
                """val\s+(\w+)\s*=\s*profileManager\s*\.\s*activeProfileSession\s*\.\s*value[\s\S]{0,400}?store\s*\(\s*\1\s*\.\s*profileId\s*\)\s*\.\s*edit"""
            ).containsMatchIn(source)
        )
    }

    @Test
    fun `WatchProgressRepository API must expose explicit profile and session scope`() {
        val source = File("app/src/main/java/com/nexio/tv/domain/repository/WatchProgressRepository.kt").readText()

        assertTrue(source.contains("observeContinueWatching(profileId: Int)"))
        assertTrue(source.contains("observeProgress(profileId: Int)"))
        assertTrue(source.contains("getProgress(profileId: Int"))
        assertTrue(source.contains("getEpisodeProgress(profileId: Int"))
        assertTrue(source.contains("getAllEpisodeProgress(profileId: Int"))
        assertTrue(source.contains("isWatched(profileId: Int"))
        assertTrue(Regex("""upsertProgress\s*\(\s*profileSession:\s*ActiveProfileSession""").containsMatchIn(source))
        assertTrue(Regex("""removeProgress\s*\(\s*profileSession:\s*ActiveProfileSession""").containsMatchIn(source))
        assertTrue(Regex("""removeFromHistory\s*\(\s*profileSession:\s*ActiveProfileSession""").containsMatchIn(source))
        assertTrue(source.contains("clearShowProgress(profileSession: ActiveProfileSession"))
        assertTrue(source.contains("markAsCompleted(profileSession: ActiveProfileSession"))
        assertTrue(Regex("""markAsCompletedBatch\s*\(\s*profileSession:\s*ActiveProfileSession""").containsMatchIn(source))
        assertTrue(source.contains("clearAll(profileSession: ActiveProfileSession"))

        assertFalse(source.contains("val continueWatching: Flow<List<WatchProgress>>"))
        assertFalse(source.contains("val allProgress: Flow<List<WatchProgress>>"))
        assertFalse(source.contains("fun getProgress(contentId: String)"))
        assertFalse(source.contains("fun getEpisodeProgress(contentId: String"))
        assertFalse(source.contains("fun getAllEpisodeProgress(contentId: String)"))
        assertFalse(source.contains("fun isWatched(contentId: String"))
        assertFalse(source.contains("suspend fun saveProgress(progress: WatchProgress"))
        assertFalse(source.contains("suspend fun removeFromHistory(contentId: String"))
        assertFalse(source.contains("suspend fun clearShowProgress(contentId: String"))
        assertFalse(source.contains("suspend fun markAsCompleted(progress: WatchProgress"))
        assertFalse(source.contains("suspend fun clearAll()"))
    }
}
