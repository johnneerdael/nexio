package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {

    @Test
    fun `UserProfile backward compatible construction compiles`() {
        val profile = UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5")
        assertEquals(1, profile.id)
        assertEquals("Default", profile.name)
        assertEquals("#1E88E5", profile.avatarColorHex)
    }

    @Test
    fun `isPrimary returns true for profile id 1`() {
        val profile = UserProfile(id = 1, name = "X", avatarColorHex = "#FFF")
        assertTrue(profile.isPrimary)
    }

    @Test
    fun `isPrimary returns false for profile id 2`() {
        val profile = UserProfile(id = 2, name = "X", avatarColorHex = "#FFF")
        assertFalse(profile.isPrimary)
    }

    @Test
    fun `avatarId defaults to null`() {
        val profile = UserProfile(id = 1, name = "X", avatarColorHex = "#FFF")
        assertNull(profile.avatarId)
    }

    @Test
    fun `pinEnabled defaults to false`() {
        val profile = UserProfile(id = 1, name = "X", avatarColorHex = "#FFF")
        assertFalse(profile.pinEnabled)
    }

    @Test
    fun `avatarId can be set explicitly`() {
        val profile = UserProfile(id = 1, name = "X", avatarColorHex = "#FFF", avatarId = "abc", pinEnabled = true)
        assertEquals("abc", profile.avatarId)
        assertTrue(profile.pinEnabled)
    }

    @Test
    fun `usesPrimaryAddons defaults to false`() {
        val profile = UserProfile(id = 1, name = "X", avatarColorHex = "#FFF")
        assertFalse(profile.usesPrimaryAddons)
    }
}
