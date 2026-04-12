package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineIconTextTest {

    @Test
    fun `inline icon scale uses override when present`() {
        val token = InlineIconTokenRegistry.resolve("dovi") ?: error("dovi token missing")
        val segment = InlineIconSegment.IconSegment(
            token = token,
            scaleOverride = 1.75f
        )

        assertEquals(1.75f, inlineIconScale(segment), 0.0001f)
    }

    @Test
    fun `inline icon scale falls back to scale class when override is absent`() {
        val titleToken = InlineIconTokenRegistry.resolve("4k") ?: error("4k token missing")
        val inlineToken = InlineIconTokenRegistry.resolve("dovi") ?: error("dovi token missing")

        assertEquals(
            1.1f,
            inlineIconScale(InlineIconSegment.IconSegment(titleToken)),
            0.0001f
        )
        assertEquals(
            1.0f,
            inlineIconScale(InlineIconSegment.IconSegment(inlineToken)),
            0.0001f
        )
    }
}
