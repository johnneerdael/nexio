package com.nexio.tv.data.local

import com.nexio.tv.testutil.testPreferencesDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesPreferencesTest {

    @Test
    fun `defaults match documented snapshot`() = runTest {
        val prefs = OpenSubtitlesPreferences(testPreferencesDataStore("os-prefs-defaults"))
        val snap = prefs.snapshot()
        assertTrue(snap.enabled)
        assertFalse(snap.onlyTrusted)
        assertFalse(snap.includeAiTranslated)
    }

    @Test
    fun `setters round-trip through the snapshot`() = runTest {
        val prefs = OpenSubtitlesPreferences(testPreferencesDataStore("os-prefs-rt"))
        prefs.setEnabled(false)
        prefs.setOnlyTrusted(true)
        prefs.setIncludeAiTranslated(true)

        val snap = prefs.snapshot()
        assertEquals(
            OpenSubtitlesPreferences.Snapshot(
                enabled = false,
                onlyTrusted = true,
                includeAiTranslated = true
            ),
            snap
        )
    }
}
