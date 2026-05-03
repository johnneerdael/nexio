package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPreferenceDataStoreMigrationTest {

    @Test
    fun `layout migration no longer rewrites retired sidebar preferences`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("migration_sidebar_collapsed_default_enabled") to false,
            booleanPreferencesKey("sidebar_collapsed_by_default") to false
        )

        applyLayoutPreferenceMigrations(prefs)

        assertFalse(prefs[booleanPreferencesKey("migration_sidebar_collapsed_default_enabled")] ?: true)
        assertFalse(prefs[booleanPreferencesKey("sidebar_collapsed_by_default")] ?: true)
    }

    @Test
    fun `fresh layout migration does not create retired sidebar keys`() {
        val prefs = mutablePreferencesOf()

        applyLayoutPreferenceMigrations(prefs)

        assertNull(prefs[booleanPreferencesKey("migration_sidebar_collapsed_default_enabled")])
        assertNull(prefs[booleanPreferencesKey("sidebar_collapsed_by_default")])
    }

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
