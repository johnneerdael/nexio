package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsDataStoreMigrationTest {

    @Test
    fun `stream selection migration forces requested defaults once`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_stream_selection_defaults_v2_enabled") to false,
            booleanPreferencesKey("uniform_stream_formatting_enabled") to false,
            booleanPreferencesKey("deduplicate_grouped_streams_enabled") to false,
            booleanPreferencesKey("filter_episode_mismatch_streams_enabled") to false,
            booleanPreferencesKey("filter_movie_year_mismatch_streams_enabled") to false,
            booleanPreferencesKey("use_parallel_connections") to false,
            intPreferencesKey("parallel_connection_count") to 4
        )

        applyPlayerSettingsMigrations(prefs)

        assertTrue(prefs[booleanPreferencesKey("migration_stream_selection_defaults_v2_enabled")] == true)
        assertTrue(prefs[booleanPreferencesKey("uniform_stream_formatting_enabled")] == true)
        assertTrue(prefs[booleanPreferencesKey("group_streams_across_addons_enabled")] == true)
        assertTrue(prefs[booleanPreferencesKey("deduplicate_grouped_streams_enabled")] == true)
        assertTrue(prefs[booleanPreferencesKey("filter_episode_mismatch_streams_enabled")] == true)
        assertTrue(prefs[booleanPreferencesKey("filter_movie_year_mismatch_streams_enabled")] == true)
        assertTrue(prefs[booleanPreferencesKey("use_parallel_connections")] == true)
        assertEquals(2, prefs[intPreferencesKey("parallel_connection_count")])
    }

    @Test
    fun `stream selection migration leaves later manual changes alone after one time upgrade`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_stream_selection_defaults_v2_enabled") to true,
            booleanPreferencesKey("uniform_stream_formatting_enabled") to false,
            booleanPreferencesKey("deduplicate_grouped_streams_enabled") to false,
            booleanPreferencesKey("filter_episode_mismatch_streams_enabled") to false,
            booleanPreferencesKey("filter_movie_year_mismatch_streams_enabled") to false,
            booleanPreferencesKey("use_parallel_connections") to false,
            intPreferencesKey("parallel_connection_count") to 4
        )

        applyPlayerSettingsMigrations(prefs)

        assertTrue(prefs[booleanPreferencesKey("group_streams_across_addons_enabled")] == true)
        assertFalse(prefs[booleanPreferencesKey("deduplicate_grouped_streams_enabled")] ?: true)
        assertFalse(prefs[booleanPreferencesKey("filter_episode_mismatch_streams_enabled")] ?: true)
        assertFalse(prefs[booleanPreferencesKey("filter_movie_year_mismatch_streams_enabled")] ?: true)
        assertFalse(prefs[booleanPreferencesKey("use_parallel_connections")] ?: true)
        assertEquals(4, prefs[intPreferencesKey("parallel_connection_count")])
    }

    @Test
    fun `uniform stream formatting is always exposed as enabled`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("uniform_stream_formatting_enabled") to false
        )

        assertTrue(uniformStreamFormattingEnabledFromPreferences(prefs))
    }
}
