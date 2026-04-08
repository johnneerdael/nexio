package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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

    @Test
    fun `audio preference migration rewrites existing preference to original once`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_preferred_audio_original_enabled") to false,
            stringPreferencesKey("preferred_audio_language") to "en"
        )

        applyPlayerSettingsMigrations(prefs)

        assertTrue(prefs[booleanPreferencesKey("migration_preferred_audio_original_enabled")] == true)
        assertEquals("original", prefs[stringPreferencesKey("preferred_audio_language")])
    }

    @Test
    fun `audio preference migration is idempotent after first upgrade`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_preferred_audio_original_enabled") to true,
            stringPreferencesKey("preferred_audio_language") to "en"
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals("en", prefs[stringPreferencesKey("preferred_audio_language")])
    }

    @Test
    fun `player settings default audio preference is original`() {
        assertEquals(AudioLanguageOption.ORIGINAL, PlayerSettings().preferredAudioLanguage)
    }

    @Test
    fun `legacy stream autoplay selection migration resets hidden autoplay mode to manual`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_legacy_stream_autoplay_selection_retired_enabled") to false,
            stringPreferencesKey("stream_auto_play_mode") to "REGEX_MATCH",
            stringPreferencesKey("stream_auto_play_source") to "INSTALLED_ADDONS_ONLY",
            stringPreferencesKey("stream_auto_play_regex") to "2160p"
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals("MANUAL", prefs[stringPreferencesKey("stream_auto_play_mode")])
        assertEquals("ALL_SOURCES", prefs[stringPreferencesKey("stream_auto_play_source")])
        assertEquals(null, prefs[stringPreferencesKey("stream_auto_play_regex")])
        assertTrue(
            prefs[booleanPreferencesKey("migration_legacy_stream_autoplay_selection_retired_enabled")] == true
        )
    }
}
