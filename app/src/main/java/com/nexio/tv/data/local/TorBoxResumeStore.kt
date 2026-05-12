package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.torBoxResumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "torbox_resume_v1")

/**
 * Per-profile resume position store for TorBox library playback.
 *
 * Keys: `torbox:p{profileId}:t{torrentId}:f{fileId}` → Long millis.
 *
 * Per CLAUDE.md #3 this is small scalar data (one Long per key); no JSON blobs. The store
 * auto-clears entries within 30 s of the end of media so completed files do not leave stale
 * resume points.
 */
@Singleton
class TorBoxResumeStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val profileIdProvider: () -> Int,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager,
    ) : this(
        dataStore = context.torBoxResumeDataStore,
        profileIdProvider = { profileManager.activeProfileId.value },
    )

    suspend fun savePosition(torrentId: Int, fileId: Int, positionMs: Long, durationMs: Long) {
        if (durationMs > 0L && positionMs >= durationMs - NEAR_END_THRESHOLD_MS) {
            clear(torrentId, fileId)
            return
        }
        val key = preferenceKey(torrentId, fileId)
        dataStore.edit { it[key] = positionMs }
    }

    suspend fun loadPosition(torrentId: Int, fileId: Int): Long? {
        val key = preferenceKey(torrentId, fileId)
        return dataStore.data.map { it[key] }.first()
    }

    suspend fun clear(torrentId: Int, fileId: Int) {
        val key = preferenceKey(torrentId, fileId)
        dataStore.edit { it.remove(key) }
    }

    private fun preferenceKey(torrentId: Int, fileId: Int): Preferences.Key<Long> {
        val profileId = profileIdProvider()
        return longPreferencesKey("torbox:p$profileId:t$torrentId:f$fileId")
    }

    companion object {
        const val NEAR_END_THRESHOLD_MS: Long = 30_000L
    }
}
