package com.nuvio.tv.data.local

import android.util.Log
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchProgressDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "watch_progress_preferences"
)

@Singleton
class WatchProgressPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WatchProgressPrefs"
    }

    private val gson = Gson()
    private val watchProgressKey = stringPreferencesKey("watch_progress_map")

    // Maximum items to keep in continue watching
    private val maxItems = 50
    
    private val maxEpisodesPerContent = 25
   
    private val maxStoredEntries = 300

    private data class StoredWatchProgress(
        val contentId: String? = null,
        val contentType: String? = null,
        val name: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val videoId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeTitle: String? = null,
        val position: Long? = null,
        val duration: Long? = null,
        val lastWatched: Long? = null,
        val addonBaseUrl: String? = null,
        val progressPercent: Float? = null,
        val source: String? = null,
        val traktPlaybackId: Long? = null,
        val traktMovieId: Int? = null,
        val traktShowId: Int? = null,
        val traktEpisodeId: Int? = null
    )

    /**
     * Get all watch progress items, sorted by last watched (most recent first)
     * For series, only returns the series-level entry (not individual episode entries)
     * to avoid duplicates in continue watching.
     */
    val allProgress: Flow<List<WatchProgress>> = context.watchProgressDataStore.data
        .map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val allItems = parseProgressMap(json)

            val contentLevelEntries = allItems.entries
                .filter { (key, progress) -> key == progress.contentId }
                .associate { it.value.contentId to it.value }
                .toMutableMap()

            val latestEpisodeFallbacks = allItems.values
                .groupBy { it.contentId }
                .mapValues { (_, items) -> items.maxByOrNull { it.lastWatched } }

            latestEpisodeFallbacks.forEach { (contentId, latest) ->
                if (contentLevelEntries[contentId] == null && latest != null) {
                    contentLevelEntries[contentId] = latest
                }
            }

            contentLevelEntries.values
                .sortedByDescending { it.lastWatched }
        }

    /**
     * Get items that are in progress (not completed)
     */
    val continueWatching: Flow<List<WatchProgress>> = allProgress.map { list ->
        list.filter { it.isInProgress() }
    }

    /**
     * Get watch progress for a specific content item
     */
    fun getProgress(contentId: String): Flow<WatchProgress?> {
        return context.watchProgressDataStore.data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            map[contentId]
        }
    }

    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return context.watchProgressDataStore.data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            map.values.find { 
                it.contentId == contentId && it.season == season && it.episode == episode 
            }
        }
    }

    /**
     * Get all episode progress for a series
     */
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return context.watchProgressDataStore.data.map { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json)
            map.values
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associateBy { (it.season!! to it.episode!!) }
        }
    }

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress) {
        context.watchProgressDataStore.edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()
            
            val key = createKey(progress)
            map[key] = progress

            if (progress.season != null && progress.episode != null) {
                val seriesKey = progress.contentId
                val existingSeriesProgress = map[seriesKey]
                
                if (existingSeriesProgress == null || progress.lastWatched > existingSeriesProgress.lastWatched) {
                    map[seriesKey] = progress.copy(videoId = progress.videoId)
                }
            }

            val pruned = pruneOldItems(map)
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    /**
     * Remove watch progress for a specific item
     */
    suspend fun removeProgress(contentId: String, season: Int? = null, episode: Int? = null) {
        context.watchProgressDataStore.edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val map = parseProgressMap(json).toMutableMap()

            val beforeSize = map.size
            Log.d(
                TAG,
                "removeProgress start contentId=$contentId season=$season episode=$episode entriesBefore=$beforeSize"
            )

            if (season != null && episode != null) {
                // Remove specific episode progress + the series-level entry
                // so the item disappears from continue watching
                val key = "${contentId}_s${season}e${episode}"
                map.remove(key)
                map.remove(contentId)
                Log.d(TAG, "removeProgress episodeKey=$key existsAfter=${map.containsKey(key)}")
            } else {
                // Remove all progress for this content
                val keysToRemove = map.keys.filter { key ->
                    key == contentId || key.startsWith("${contentId}_s")
                }
                Log.d(TAG, "removeProgress removingKeys=${keysToRemove.joinToString()}")
                keysToRemove.forEach { map.remove(it) }
            }

            Log.d(TAG, "removeProgress complete contentId=$contentId entriesAfter=${map.size}")
            preferences[watchProgressKey] = gson.toJson(map)
        }
    }

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress) {
        val completedProgress = progress.copy(
            position = progress.duration,
            lastWatched = System.currentTimeMillis()
        )
        saveProgress(completedProgress)
    }

    /**
     * Returns the raw key→WatchProgress map from DataStore (for sync push).
     */
    suspend fun getAllRawEntries(): Map<String, WatchProgress> {
        val preferences = context.watchProgressDataStore.data.first()
        val json = preferences[watchProgressKey] ?: "{}"
        return parseProgressMap(json)
    }

    /**
     * Merges remote entries into local storage. Newer lastWatched wins per key.
     */
    suspend fun mergeRemoteEntries(remoteEntries: Map<String, WatchProgress>) {
        Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${remoteEntries.size} remote entries")
        if (remoteEntries.isEmpty()) return
        context.watchProgressDataStore.edit { preferences ->
            val json = preferences[watchProgressKey] ?: "{}"
            val local = parseProgressMap(json).toMutableMap()
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${local.size} existing local entries")

            for ((key, remote) in remoteEntries) {
                val existing = local[key]
                if (existing == null || remote.lastWatched > existing.lastWatched) {
                    local[key] = remote
                    Log.d("WatchProgressPrefs", "  merged key=$key (existing=${existing != null})")
                } else {
                    Log.d("WatchProgressPrefs", "  skipped key=$key (local is newer)")
                }
            }

            val pruned = pruneOldItems(local)
            Log.d("WatchProgressPrefs", "mergeRemoteEntries: ${pruned.size} entries after prune, writing to DataStore")
            preferences[watchProgressKey] = gson.toJson(pruned)
        }
    }

    /**
     * Clear all watch progress
     */
    suspend fun clearAll() {
        context.watchProgressDataStore.edit { preferences ->
            preferences.remove(watchProgressKey)
        }
    }

    private fun createKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private fun parseProgressMap(json: String): Map<String, WatchProgress> {
        return try {
            val type = object : TypeToken<Map<String, StoredWatchProgress>>() {}.type
            val rawMap: Map<String, StoredWatchProgress> = gson.fromJson(json, type) ?: emptyMap()
            rawMap.mapNotNull { (key, rawEntry) ->
                rawEntry.toWatchProgressOrNull(key)?.let { key to it }
            }.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse progress data", e)
            emptyMap()
        }
    }

    private fun StoredWatchProgress.toWatchProgressOrNull(key: String): WatchProgress? {
        val resolvedContentId = contentId?.takeIf { it.isNotBlank() }
        val resolvedContentType = contentType?.takeIf { it.isNotBlank() }
        val resolvedVideoId = videoId?.takeIf { it.isNotBlank() } ?: resolvedContentId
        val resolvedLastWatched = lastWatched

        if (resolvedContentId == null || resolvedContentType == null || resolvedVideoId == null || resolvedLastWatched == null) {
            Log.w(TAG, "Dropping invalid watch progress entry for key=$key")
            return null
        }

        return WatchProgress(
            contentId = resolvedContentId,
            contentType = resolvedContentType,
            name = name.orEmpty(),
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = resolvedVideoId,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            position = position ?: 0L,
            duration = duration ?: 0L,
            lastWatched = resolvedLastWatched,
            addonBaseUrl = addonBaseUrl,
            progressPercent = progressPercent,
            source = source?.takeIf { it.isNotBlank() } ?: WatchProgress.SOURCE_LOCAL,
            traktPlaybackId = traktPlaybackId,
            traktMovieId = traktMovieId,
            traktShowId = traktShowId,
            traktEpisodeId = traktEpisodeId
        )
    }

    private fun pruneOldItems(map: MutableMap<String, WatchProgress>): Map<String, WatchProgress> {
        if (map.isEmpty()) return map

        val keepContentIds = map.values
            .groupBy { it.contentId }
            .mapValues { (_, items) -> items.maxOf { it.lastWatched } }
            .entries
            .sortedByDescending { it.value }
            .take(maxItems)
            .map { it.key }
        val keepContentIdSet = keepContentIds.toSet()

        val filteredByContent = map.filterValues { it.contentId in keepContentIdSet }
        val boundedByContent = mutableMapOf<String, WatchProgress>()

        keepContentIds.forEach { contentId ->
            val entriesForContent = filteredByContent.filterValues { it.contentId == contentId }

            // Keep canonical content-level record when present.
            entriesForContent[contentId]?.let { boundedByContent[contentId] = it }

            val recentEpisodeEntries = entriesForContent
                .filterKeys { it != contentId }
                .entries
                .sortedByDescending { it.value.lastWatched }
                .take(maxEpisodesPerContent)

            recentEpisodeEntries.forEach { (key, value) ->
                boundedByContent[key] = value
            }
        }

        if (boundedByContent.size <= maxStoredEntries) return boundedByContent

        val pinnedContentKeys = keepContentIds.filter { boundedByContent.containsKey(it) }.toSet()
        val remainingSlots = (maxStoredEntries - pinnedContentKeys.size).coerceAtLeast(0)

        val limited = mutableMapOf<String, WatchProgress>()
        pinnedContentKeys.forEach { key ->
            boundedByContent[key]?.let { limited[key] = it }
        }

        boundedByContent.entries
            .asSequence()
            .filter { (key, _) -> key !in pinnedContentKeys }
            .sortedByDescending { (_, value) -> value.lastWatched }
            .take(remainingSlots)
            .forEach { (key, value) ->
                limited[key] = value
            }

        return limited
    }
}
