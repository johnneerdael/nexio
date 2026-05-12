package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.core.sync.AccountSettingsSectionKey
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

    private fun accountSettingsSectionKey(section: AccountSettingsSectionKey): Preferences.Key<Long> {
        return longPreferencesKey("watermark.${SyncWatermarkSurface.ACCOUNT_SETTINGS_SECTION.name}:${section.key}")
    }

    private fun accountSettingsSectionBaselineKey(section: AccountSettingsSectionKey): Preferences.Key<String> {
        return stringPreferencesKey("baseline.${SyncWatermarkSurface.ACCOUNT_SETTINGS_SECTION.name}:${section.key}")
    }

    suspend fun get(surface: SyncWatermarkSurface, profileId: Int?): Long {
        return dataStore.data.first()[key(surface, profileId)] ?: 0L
    }

    suspend fun set(surface: SyncWatermarkSurface, profileId: Int?, ms: Long) {
        dataStore.edit { prefs -> prefs[key(surface, profileId)] = ms }
    }

    suspend fun getAccountSettingsSection(section: AccountSettingsSectionKey): Long {
        return dataStore.data.first()[accountSettingsSectionKey(section)] ?: 0L
    }

    suspend fun setAccountSettingsSection(section: AccountSettingsSectionKey, ms: Long) {
        dataStore.edit { prefs -> prefs[accountSettingsSectionKey(section)] = ms }
    }

    suspend fun getAccountSettingsSectionBaselines(): Map<AccountSettingsSectionKey, String> {
        val prefs = dataStore.data.first()
        return AccountSettingsSectionKey.entries.mapNotNull { section ->
            prefs[accountSettingsSectionBaselineKey(section)]?.let { baseline ->
                section to baseline
            }
        }.toMap()
    }

    suspend fun setAccountSettingsSectionBaselines(baselines: Map<AccountSettingsSectionKey, String>) {
        if (baselines.isEmpty()) return
        dataStore.edit { prefs ->
            baselines.forEach { (section, baseline) ->
                prefs[accountSettingsSectionBaselineKey(section)] = baseline
            }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
