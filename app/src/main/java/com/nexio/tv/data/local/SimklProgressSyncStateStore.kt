package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SimklProgressSyncState(
    val lastAllActivityAt: String? = null,
    val lastPlaybackActivityAt: String? = null,
    val lastRemovedFromListActivityAt: String? = null
)

@Singleton
class SimklProgressSyncStateStore private constructor(
    private val context: Context,
    private val activeProfileId: () -> Int,
    private val injectedProfileManager: ProfileManager?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ) : this(
        context = context,
        activeProfileId = { profileManager.activeProfileId.value },
        injectedProfileManager = profileManager
    )

    constructor(context: Context) : this(
        context = context,
        activeProfileId = { 1 },
        injectedProfileManager = null
    )

    companion object {
        internal const val BASE_PREFS_NAME = "simkl_progress_sync_state"
        private const val KEY_LAST_ALL = "last_all_activity_at"
        private const val KEY_LAST_PLAYBACK = "last_playback_activity_at"
        private const val KEY_LAST_REMOVED = "last_removed_from_list_activity_at"
    }

    private val profileManager: ProfileManager
        get() = injectedProfileManager ?: error("ProfileManager unavailable")

    private fun injectedPrefsName(profileId: Int): String =
        profilePrefsName(BASE_PREFS_NAME, profileId)

    private fun prefsName(profileId: Int = activeProfileId()): String =
        if (injectedProfileManager != null) {
            injectedPrefsName(profileId)
        } else {
            profilePrefsName(BASE_PREFS_NAME, profileId)
        }

    fun read(): SimklProgressSyncState = read(activeProfileId())

    fun read(profileId: Int): SimklProgressSyncState {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        return SimklProgressSyncState(
            lastAllActivityAt = prefs.getString(KEY_LAST_ALL, null),
            lastPlaybackActivityAt = prefs.getString(KEY_LAST_PLAYBACK, null),
            lastRemovedFromListActivityAt = prefs.getString(KEY_LAST_REMOVED, null)
        )
    }

    fun write(state: SimklProgressSyncState) = write(state, activeProfileId())

    fun write(state: SimklProgressSyncState, profileId: Int) {
        context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ALL, state.lastAllActivityAt)
            .putString(KEY_LAST_PLAYBACK, state.lastPlaybackActivityAt)
            .putString(KEY_LAST_REMOVED, state.lastRemovedFromListActivityAt)
            .commit()
    }

    fun clear() = clear(activeProfileId())

    fun clear(profileId: Int) {
        context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_ALL)
            .remove(KEY_LAST_PLAYBACK)
            .remove(KEY_LAST_REMOVED)
            .commit()
    }

    fun currentProfileId(): Int = activeProfileId()
}
