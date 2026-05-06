package com.nexio.tv.core.player

import com.nexio.tv.domain.model.AddonStreams
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamAutoPlaySelectorAnimePriorityTest {

    private fun streams(name: String, isAnimeBucket: Boolean = false): AddonStreams =
        AddonStreams(addonName = name, addonLogo = null, streams = emptyList(), isAnimeBucket = isAnimeBucket)

    @Test
    fun `anime bucket sections sort above non-anime regardless of installed order`() {
        val installedOrder = listOf("Generic-A", "Generic-B", "Anime-A", "Anime-B")
        val input = listOf(
            streams("Generic-A"),
            streams("Anime-B", isAnimeBucket = true),
            streams("Generic-B"),
            streams("Anime-A", isAnimeBucket = true),
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(listOf("Anime-A", "Anime-B", "Generic-A", "Generic-B"), ordered)
    }

    @Test
    fun `within each bucket installedOrder is preserved`() {
        val installedOrder = listOf("Anime-First", "Anime-Second", "Generic-First", "Generic-Second")
        val input = listOf(
            streams("Generic-Second"),
            streams("Anime-Second", isAnimeBucket = true),
            streams("Generic-First"),
            streams("Anime-First", isAnimeBucket = true),
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(
            listOf("Anime-First", "Anime-Second", "Generic-First", "Generic-Second"),
            ordered
        )
    }

    @Test
    fun `all-non-anime input is byte-identical to legacy ordering`() {
        val installedOrder = listOf("A", "B", "C")
        val input = listOf(streams("C"), streams("A"), streams("B"))

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(listOf("A", "B", "C"), ordered)
    }

    @Test
    fun `unknown addon names land at the end of their bucket`() {
        val installedOrder = listOf("Anime-A", "Generic-A")
        val input = listOf(
            streams("Unknown-Anime", isAnimeBucket = true),
            streams("Anime-A", isAnimeBucket = true),
            streams("Unknown-Generic"),
            streams("Generic-A"),
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(
            listOf("Anime-A", "Unknown-Anime", "Generic-A", "Unknown-Generic"),
            ordered
        )
    }

    @Test
    fun `empty anime-bucket section stays in its bucket and does not collapse generics`() {
        // An anime-tagged addon returned zero streams (still emitted as an
        // empty section). It must remain in the anime bucket above the
        // generic addons so the section header order stays correct.
        val installedOrder = listOf("Anime-A", "Generic-A")
        val input = listOf(
            streams("Generic-A"),
            streams("Anime-A", isAnimeBucket = true),
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(listOf("Anime-A", "Generic-A"), ordered)
    }
}
