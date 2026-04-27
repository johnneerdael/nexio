package com.nexio.tv.core.integration

import com.nexio.tv.core.profile.ProfileSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileExecutionContextAccessorsTest {
    @Test
    fun `traktAccount accessor reads from accounts map`() {
        val ref = ProviderAccountRef(IntegrationProvider.TRAKT, "trakt-h", null)
        val ctx = ProfileExecutionContext(
            profileId = 1,
            sessionId = "s",
            displayLanguage = "en",
            region = "US",
            accounts = mapOf(IntegrationProvider.TRAKT to ref)
        )
        assertEquals(ref, ctx.traktAccount)
        assertNull(ctx.simklAccount)
        assertNull(ctx.mdblistAccount)
    }

    @Test
    fun `settings is exposed when provided`() {
        val settings = ProfileSettingsSnapshot(displayLanguage = "nl", region = "NL", autoplay = true)
        val ctx = ProfileExecutionContext(
            profileId = 1,
            sessionId = "s",
            displayLanguage = "nl",
            region = "NL",
            settings = settings
        )
        assertEquals(settings, ctx.settings)
    }

    @Test
    fun `settings defaults to null for backwards compatibility`() {
        val ctx = ProfileExecutionContext(
            profileId = 1,
            sessionId = "s",
            displayLanguage = "en",
            region = "US"
        )
        assertNull(ctx.settings)
    }
}
