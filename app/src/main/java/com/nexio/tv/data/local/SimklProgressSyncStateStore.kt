package com.nexio.tv.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SimklProgressSyncState(
    val lastAllActivityAt: String? = null,
    val lastPlaybackActivityAt: String? = null,
    val lastRemovedFromListActivityAt: String? = null
)

@Singleton
class SimklProgressSyncStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "simkl_progress_sync_state"
        private const val KEY_LAST_ALL = "last_all_activity_at"
        private const val KEY_LAST_PLAYBACK = "last_playback_activity_at"
        private const val KEY_LAST_REMOVED = "last_removed_from_list_activity_at"
    }

    fun read(): SimklProgressSyncState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return SimklProgressSyncState(
            lastAllActivityAt = prefs.getString(KEY_LAST_ALL, null),
            lastPlaybackActivityAt = prefs.getString(KEY_LAST_PLAYBACK, null),
            lastRemovedFromListActivityAt = prefs.getString(KEY_LAST_REMOVED, null)
        )
    }

    fun write(state: SimklProgressSyncState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ALL, state.lastAllActivityAt)
            .putString(KEY_LAST_PLAYBACK, state.lastPlaybackActivityAt)
            .putString(KEY_LAST_REMOVED, state.lastRemovedFromListActivityAt)
            .commit()
    }

    fun clear() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_ALL)
            .remove(KEY_LAST_PLAYBACK)
            .remove(KEY_LAST_REMOVED)
            .commit()
    }
}
