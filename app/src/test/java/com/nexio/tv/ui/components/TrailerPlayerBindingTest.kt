package com.nexio.tv.ui.components

import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class TrailerPlayerBindingTest {

    @Test
    fun `player view binding updates when trailer player instance changes`() {
        val view = mockk<PlayerView>(relaxed = true)
        val previousPlayer = mockk<Player>()
        val nextPlayer = mockk<Player>()
        every { view.player } returns previousPlayer

        bindTrailerPlayerView(view, nextPlayer)

        verify { view.player = nextPlayer }
    }

    @Test
    fun `player view binding is skipped when trailer player instance is unchanged`() {
        val view = mockk<PlayerView>(relaxed = true)
        val player = mockk<Player>()
        every { view.player } returns player

        bindTrailerPlayerView(view, player)

        verify(exactly = 0) { view.player = any() }
    }
}
