package com.nexio.tv.data.local

import com.nexio.tv.core.sync.profilePrefsName
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePrefsNameTest {
    @Test
    fun `profile 1 uses bare preferences name`() {
        assertEquals("trakt_library_snapshot", profilePrefsName("trakt_library_snapshot", 1))
    }

    @Test
    fun `profile 2 uses p2 suffix`() {
        assertEquals("trakt_library_snapshot_p2", profilePrefsName("trakt_library_snapshot", 2))
    }

    @Test
    fun `profile 3 uses p3 suffix`() {
        assertEquals("trakt_library_snapshot_p3", profilePrefsName("trakt_library_snapshot", 3))
    }

    @Test
    fun `profile 4 uses p4 suffix`() {
        assertEquals("trakt_library_snapshot_p4", profilePrefsName("trakt_library_snapshot", 4))
    }

    @Test
    fun `suffix is appended after versioned base name`() {
        assertEquals(
            "simkl_discovery_snapshot_v2_p2",
            profilePrefsName("simkl_discovery_snapshot_v2", 2)
        )
    }
}
