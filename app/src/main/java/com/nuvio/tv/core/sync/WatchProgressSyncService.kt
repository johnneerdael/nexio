package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.remote.supabase.SupabaseWatchProgress
import com.nuvio.tv.domain.model.WatchProgress
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchProgressSyncService"

@Singleton
class WatchProgressSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktAuthDataStore: TraktAuthDataStore
) {
    /**
     * Push all local watch progress to Supabase via RPC.
     * Skips if Trakt is connected (Trakt handles progress when active).
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping watch progress push")
                return@withContext Result.success(Unit)
            }

            val entries = watchProgressPreferences.getAllRawEntries()
            Log.d(TAG, "pushToRemote: ${entries.size} local entries to push")
            entries.forEach { (key, progress) ->
                Log.d(TAG, "  push entry: key=$key contentId=${progress.contentId} type=${progress.contentType} pos=${progress.position} dur=${progress.duration} lastWatched=${progress.lastWatched}")
            }

            if (entries.isEmpty()) {
                Log.d(TAG, "pushToRemote: nothing to push, skipping RPC")
                return@withContext Result.success(Unit)
            }

            val params = buildJsonObject {
                put("p_entries", buildJsonArray {
                    entries.forEach { (key, progress) ->
                        addJsonObject {
                            put("content_id", progress.contentId)
                            put("content_type", progress.contentType)
                            put("video_id", progress.videoId)
                            progress.season?.let { put("season", it) }
                            progress.episode?.let { put("episode", it) }
                            put("position", progress.position)
                            put("duration", progress.duration)
                            put("last_watched", progress.lastWatched)
                            put("progress_key", key)
                        }
                    }
                })
            }
            postgrest.rpc("sync_push_watch_progress", params)

            Log.d(TAG, "Pushed ${entries.size} watch progress entries to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push watch progress to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull watch progress from Supabase via SECURITY DEFINER RPC.
     * Uses get_sync_owner() server-side to fetch the correct user's data,
     * bypassing RLS (which would block linked devices from reading owner data).
     * Skips if Trakt is connected. Caller is responsible for merging into local.
     */
    suspend fun pullFromRemote(): List<Pair<String, WatchProgress>> = withContext(Dispatchers.IO) {
        try {
            if (traktAuthDataStore.isAuthenticated.first()) {
                Log.d(TAG, "Trakt connected, skipping watch progress pull")
                return@withContext emptyList()
            }

            val response = postgrest.rpc("sync_pull_watch_progress")
            val remote = response.decodeList<SupabaseWatchProgress>()

            Log.d(TAG, "pullFromRemote: fetched ${remote.size} entries from Supabase via RPC")
            remote.forEach { entry ->
                Log.d(TAG, "  pull entry: key=${entry.progressKey} contentId=${entry.contentId} type=${entry.contentType} pos=${entry.position} dur=${entry.duration} lastWatched=${entry.lastWatched}")
            }

            remote.map { entry ->
                entry.progressKey to WatchProgress(
                    contentId = entry.contentId,
                    contentType = entry.contentType,
                    name = "",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = entry.videoId,
                    season = entry.season,
                    episode = entry.episode,
                    episodeTitle = null,
                    position = entry.position,
                    duration = entry.duration,
                    lastWatched = entry.lastWatched,
                    source = WatchProgress.SOURCE_LOCAL
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watch progress from remote", e)
            emptyList()
        }
    }
}
