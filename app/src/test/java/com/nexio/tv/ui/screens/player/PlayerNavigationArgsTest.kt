package com.nexio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerNavigationArgsTest {

    @Test
    fun `decodes encoded stream url from navigation state`() {
        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "asset%3A%2F%2F%2Ftruehd.mkv",
                    "title" to "TrueHD%20Validation"
                )
            )
        )

        assertEquals("asset:///truehd.mkv", args.streamUrl)
        assertEquals("TrueHD Validation", args.title)
    }
}
