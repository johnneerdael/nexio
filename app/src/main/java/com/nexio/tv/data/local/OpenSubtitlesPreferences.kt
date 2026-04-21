package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.openSubtitlesPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "open_subtitles_preferences"
)

/**
 * User-controllable knobs for the native OpenSubtitles source.
 *
 * `enabled` is on by default — the source is the primary fallback when
 * Stremio addons return nothing. The `onlyTrusted` and `includeAiTranslated`
 * flags filter the rest.opensubtitles.org rows before they reach the UI.
 */
@Singleton
class OpenSubtitlesPreferences internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.openSubtitlesPreferencesDataStore)

    val enabled: Flow<Boolean> = dataStore.data.map { it[KEY_ENABLED] ?: DEFAULT_ENABLED }
    val onlyTrusted: Flow<Boolean> = dataStore.data.map { it[KEY_ONLY_TRUSTED] ?: DEFAULT_ONLY_TRUSTED }
    val includeAiTranslated: Flow<Boolean> = dataStore.data.map { it[KEY_INCLUDE_AI] ?: DEFAULT_INCLUDE_AI }

    suspend fun snapshot(): Snapshot = Snapshot(
        enabled = enabled.first(),
        onlyTrusted = onlyTrusted.first(),
        includeAiTranslated = includeAiTranslated.first()
    )

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setOnlyTrusted(value: Boolean) {
        dataStore.edit { it[KEY_ONLY_TRUSTED] = value }
    }

    suspend fun setIncludeAiTranslated(value: Boolean) {
        dataStore.edit { it[KEY_INCLUDE_AI] = value }
    }

    data class Snapshot(
        val enabled: Boolean,
        val onlyTrusted: Boolean,
        val includeAiTranslated: Boolean
    )

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_ONLY_TRUSTED = false
        const val DEFAULT_INCLUDE_AI = false

        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_ONLY_TRUSTED = booleanPreferencesKey("only_trusted")
        private val KEY_INCLUDE_AI = booleanPreferencesKey("include_ai_translated")
    }
}
