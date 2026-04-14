package com.nexio.tv.data.local

import com.nexio.tv.core.profile.FakeProfileManager
import com.nexio.tv.domain.model.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeDataStoreProfileTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `theme persists independently per profile`() = runTest {
        val factory = FakeProfileDataStoreFactory(tempFolder.newFolder())
        val profileManager = FakeProfileManager(initialProfileId = 1)
        val store = ThemeDataStore(factory, profileManager)

        // Profile 1: set theme to CRIMSON
        store.setTheme(AppTheme.CRIMSON)
        assertEquals(AppTheme.CRIMSON, store.selectedTheme.first())

        // Switch to profile 2: should see default WHITE
        profileManager.switchTo(2)
        assertEquals(AppTheme.WHITE, store.selectedTheme.first())

        // Profile 2: set theme to OCEAN
        store.setTheme(AppTheme.OCEAN)
        assertEquals(AppTheme.OCEAN, store.selectedTheme.first())

        // Switch back to profile 1: should still see CRIMSON
        profileManager.switchTo(1)
        assertEquals(AppTheme.CRIMSON, store.selectedTheme.first())
    }
}
