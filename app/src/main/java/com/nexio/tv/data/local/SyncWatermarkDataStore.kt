package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.core.sync.SyncWatermarkSurface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncWatermarkPrefs: DataStore<Preferences> by preferencesDataStore(name = "sync_watermarks")

/**
 * Persists the per-surface `updated_at_ms` watermark seen on the last successful
 * pull from Supabase. Carried as `p_base_updated_at_ms` on every Contract v10
 * push so the server can refuse stale writes.
 *
 * Values are small `Long` scalars — fits CLAUDE.md rule #3's scalars-only caveat
 * on Jetpack DataStore.
 */
@Singleton
class SyncWatermarkDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore = context.syncWatermarkPrefs

    private fun key(surface: SyncWatermarkSurface, profileId: Int?): Preferences.Key<Long> {
        val suffix = if (profileId == null) surface.name else "${surface.name}:$profileId"
        return longPreferencesKey("watermark.$suffix")
    }

    suspend fun get(surface: SyncWatermarkSurface, profileId: Int?): Long {
        return dataStore.data.first()[key(surface, profileId)] ?: 0L
    }

    suspend fun set(surface: SyncWatermarkSurface, profileId: Int?, ms: Long) {
        dataStore.edit { prefs -> prefs[key(surface, profileId)] = ms }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
