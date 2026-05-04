package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeRailKeyTest {
    @Test
    fun `equal values produce equal keys`() {
        assertEquals(HomeRailKey("tmdb:popular:movies"), HomeRailKey("tmdb:popular:movies"))
    }

    @Test
    fun `different values are not equal`() {
        assertNotEquals(HomeRailKey("a"), HomeRailKey("b"))
    }

    @Test
    fun `value is preserved as-is`() {
        assertEquals("trakt:user-list:abc:def", HomeRailKey("trakt:user-list:abc:def").value)
    }
}
