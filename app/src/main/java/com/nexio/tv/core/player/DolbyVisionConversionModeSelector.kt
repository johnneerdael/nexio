package com.nexio.tv.core.player

internal object DolbyVisionConversionModeSelector {
    const val MODE_PROFILE_8_1 = 2
    const val MODE_PROFILE_8_1_PRESERVE_MAPPING = 5

    fun selectedMode(
        sourceProfile: Int?,
        preserveMappingEnabled: Boolean,
        allowDv5Conversion: Boolean
    ): Int? {
        return when (sourceProfile) {
            7 -> if (preserveMappingEnabled) {
                MODE_PROFILE_8_1_PRESERVE_MAPPING
            } else {
                MODE_PROFILE_8_1
            }
            5 -> if (allowDv5Conversion) MODE_PROFILE_8_1 else null
            10 -> null
            else -> null
        }
    }
}
