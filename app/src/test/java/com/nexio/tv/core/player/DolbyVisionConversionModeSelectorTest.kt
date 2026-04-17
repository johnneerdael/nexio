package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DolbyVisionConversionModeSelectorTest {

    @Test
    fun `profile 7 without preserve mapping selects mode 2`() {
        assertEquals(
            DolbyVisionConversionModeSelector.MODE_PROFILE_8_1,
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = 7,
                preserveMappingEnabled = false,
                allowDv5Conversion = false
            )
        )
    }

    @Test
    fun `profile 7 with preserve mapping selects mode 5`() {
        assertEquals(
            DolbyVisionConversionModeSelector.MODE_PROFILE_8_1_PRESERVE_MAPPING,
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = 7,
                preserveMappingEnabled = true,
                allowDv5Conversion = false
            )
        )
    }

    @Test
    fun `profile 5 requires dv5 conversion opt in`() {
        assertNull(
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = 5,
                preserveMappingEnabled = false,
                allowDv5Conversion = false
            )
        )
        assertEquals(
            DolbyVisionConversionModeSelector.MODE_PROFILE_8_1,
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = 5,
                preserveMappingEnabled = false,
                allowDv5Conversion = true
            )
        )
    }

    @Test
    fun `unsupported profile 8 selects no mode`() {
        assertNull(
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = 8,
                preserveMappingEnabled = false,
                allowDv5Conversion = true
            )
        )
    }

    @Test
    fun `profile 10 selects no conversion mode`() {
        assertNull(
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = 10,
                preserveMappingEnabled = true,
                allowDv5Conversion = true
            )
        )
    }

    @Test
    fun `null profile selects no mode`() {
        assertNull(
            DolbyVisionConversionModeSelector.selectedMode(
                sourceProfile = null,
                preserveMappingEnabled = false,
                allowDv5Conversion = true
            )
        )
    }

    @Test
    fun `bridge defaults to profile 8 1 mode`() {
        assertEquals(2, DolbyVisionConversionModeSelector.MODE_PROFILE_8_1)
        assertEquals(5, DolbyVisionConversionModeSelector.MODE_PROFILE_8_1_PRESERVE_MAPPING)
        assertEquals(
            DolbyVisionConversionModeSelector.MODE_PROFILE_8_1,
            DEFAULT_DV7_CONVERSION_MODE
        )
    }
}
