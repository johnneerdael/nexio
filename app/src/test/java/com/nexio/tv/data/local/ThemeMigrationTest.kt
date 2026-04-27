package com.nexio.tv.data.local

import com.nexio.tv.domain.model.AppTheme
import org.junit.Test
import org.junit.Assert.assertEquals

class ThemeMigrationTest {
    @Test fun `legacy WHITE value migrates to CRIMSON`() {
        assertEquals(AppTheme.CRIMSON, migrateThemePreference("WHITE"))
    }

    @Test fun `valid theme values pass through unchanged`() {
        assertEquals(AppTheme.OCEAN, migrateThemePreference("OCEAN"))
        assertEquals(AppTheme.AMBER, migrateThemePreference("AMBER"))
        assertEquals(AppTheme.CRIMSON, migrateThemePreference("CRIMSON"))
    }

    @Test fun `unknown values fall back to CRIMSON`() {
        assertEquals(AppTheme.CRIMSON, migrateThemePreference("garbage"))
        assertEquals(AppTheme.CRIMSON, migrateThemePreference(null))
    }
}
