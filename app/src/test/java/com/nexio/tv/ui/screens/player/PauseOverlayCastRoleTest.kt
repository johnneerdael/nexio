package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseOverlayCastRoleTest {

    @Test
    fun `normalizes production roles like the detail cast section`() {
        assertEquals(
            "Creator",
            pauseOverlayCastDisplayRole(
                character = "creator",
                creatorLabel = "Creator",
                directorLabel = "Director",
                writerLabel = "Writer"
            )
        )
        assertEquals(
            "Director",
            pauseOverlayCastDisplayRole(
                character = "DIRECTOR",
                creatorLabel = "Creator",
                directorLabel = "Director",
                writerLabel = "Writer"
            )
        )
        assertEquals(
            "Writer",
            pauseOverlayCastDisplayRole(
                character = "Writer",
                creatorLabel = "Creator",
                directorLabel = "Director",
                writerLabel = "Writer"
            )
        )
    }

    @Test
    fun `keeps actor character names as provided`() {
        assertEquals(
            "Cate Randa",
            pauseOverlayCastDisplayRole(
                character = "Cate Randa",
                creatorLabel = "Creator",
                directorLabel = "Director",
                writerLabel = "Writer"
            )
        )
    }

    @Test
    fun `returns null for blank role`() {
        assertNull(
            pauseOverlayCastDisplayRole(
                character = " ",
                creatorLabel = "Creator",
                directorLabel = "Director",
                writerLabel = "Writer"
            )
        )
    }
}
