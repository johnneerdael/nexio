package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPreferenceDataStoreMigrationTest {

    @Test
    fun `hide unreleased migration resets obsolete preference to false`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_hide_unreleased_default_enabled") to false,
            booleanPreferencesKey("hide_unreleased_content") to true
        )

        applyLayoutPreferenceMigrations(prefs)

        assertFalse(prefs[booleanPreferencesKey("hide_unreleased_content")] ?: true)
        assertTrue(prefs[booleanPreferencesKey("migration_hide_unreleased_default_enabled")] == true)
    }
}
