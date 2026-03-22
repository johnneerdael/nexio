package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineIconTokenRegistryTest {

    @Test
    fun `token registry marks title badges as prominent`() {
        val token = InlineIconTokenRegistry.resolve("4k")

        assertEquals(ScaleClass.TITLE_PROMINENT, token?.scaleClass)
    }
}
