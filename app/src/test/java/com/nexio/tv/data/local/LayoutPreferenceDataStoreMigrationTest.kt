package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPreferenceDataStoreMigrationTest {

    @Test
    fun `collapse sidebar migration forces requested default once`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_sidebar_collapsed_default_enabled") to false,
            booleanPreferencesKey("sidebar_collapsed_by_default") to false
        )

        applyLayoutPreferenceMigrations(prefs)

        assertTrue(prefs[booleanPreferencesKey("migration_sidebar_collapsed_default_enabled")] == true)
        assertTrue(prefs[booleanPreferencesKey("sidebar_collapsed_by_default")] == true)
    }

    @Test
    fun `collapse sidebar migration leaves later manual changes alone after one time upgrade`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_sidebar_collapsed_default_enabled") to true,
            booleanPreferencesKey("sidebar_collapsed_by_default") to false
        )

        applyLayoutPreferenceMigrations(prefs)

        assertFalse(prefs[booleanPreferencesKey("sidebar_collapsed_by_default")] ?: true)
    }

    @Test
    fun `collapse sidebar defaults enabled for fresh installs`() {
        val prefs = mutablePreferencesOf()

        assertTrue(sidebarCollapsedByDefaultFromPreferences(prefs))
    }
}
