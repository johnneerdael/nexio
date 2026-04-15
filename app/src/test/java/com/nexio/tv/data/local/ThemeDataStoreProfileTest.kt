package com.nexio.tv.data.local

import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.testutil.themeDataStoreForTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeDataStoreProfileTest {
    @Test
    fun `theme persists independently per profile`() = runTest {
        val activeProfileId = MutableStateFlow(1)
        val store = themeDataStoreForTest(activeProfileId)

        // Profile 1: set theme to CRIMSON
        store.setTheme(AppTheme.CRIMSON)
        assertEquals(AppTheme.CRIMSON, store.selectedTheme.first())

        // Switch to profile 2: should see default WHITE
        activeProfileId.value = 2
        assertEquals(AppTheme.WHITE, store.selectedTheme.first())

        // Profile 2: set theme to OCEAN
        store.setTheme(AppTheme.OCEAN)
        assertEquals(AppTheme.OCEAN, store.selectedTheme.first())

        // Switch back to profile 1: should still see CRIMSON
        activeProfileId.value = 1
        assertEquals(AppTheme.CRIMSON, store.selectedTheme.first())
    }
}
