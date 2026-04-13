package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineChipTokenRegistryTest {
    @Test
    fun `tokenize parses known chip tokens`() {
        val segments = InlineChipTokenRegistry.tokenize("A [[chip:cached]] B [[chip:torrent]]")
        val chips = segments.filterIsInstance<InlineChipSegment.ChipSegment>()

        assertEquals(listOf(StreamBadgeKind.CACHED, StreamBadgeKind.TORRENT), chips.map { it.kind })
    }

    @Test
    fun `tokenize strips unknown chip tokens to readable text`() {
        val segments = InlineChipTokenRegistry.tokenize("A [[chip:unknown_badge]]")
        val flattened = segments.joinToString("") {
            when (it) {
                is InlineChipSegment.TextSegment -> it.text
                is InlineChipSegment.ChipSegment -> it.kind.name
            }
        }

        assertEquals("A unknown_badge", flattened)
    }

    @Test
    fun `containsToken detects any chip token`() {
        assertTrue(InlineChipTokenRegistry.containsToken("before [[chip:cached]] after"))
    }
}
