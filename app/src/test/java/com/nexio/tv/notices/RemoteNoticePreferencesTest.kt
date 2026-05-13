package com.nexio.tv.notices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNoticePreferencesTest {

    @Test
    fun `baseline is absent by default and can be set once`() = runTest {
        val prefs = RemoteNoticePreferences(createDataStore())

        assertNull(prefs.noticeBaselineAt.first())

        val baseline = Instant.parse("2026-05-12T10:00:00Z")
        prefs.setNoticeBaselineAtIfAbsent(baseline)
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-13T10:00:00Z"))

        assertEquals(baseline, prefs.noticeBaselineAt.first())
    }

    @Test
    fun `seen ids accumulate without duplicates`() = runTest {
        val prefs = RemoteNoticePreferences(createDataStore())

        assertTrue(prefs.seenNoticeIds.first().isEmpty())

        prefs.markSeen("a")
        prefs.markSeen("b")
        prefs.markSeen("a")

        assertEquals(setOf("a", "b"), prefs.seenNoticeIds.first())
    }

    @Test
    fun `last check timestamp is stored`() = runTest {
        val prefs = RemoteNoticePreferences(createDataStore())

        prefs.setLastCheckAtMs(1234L)

        assertEquals(1234L, prefs.lastCheckAtMs.first())
    }

    private fun TestScope.createDataStore(): DataStore<Preferences> {
        val tempFile = File.createTempFile("remote_notice_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFile }
        )
    }
}
