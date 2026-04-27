package com.nexio.tv.core.player

data class BurnInProtectionState(
    val enabled: Boolean,
    val verticalDeltaPercent: Float,
    val horizontalOffsetPx: Float,
) {
    companion object {
        val DISABLED = BurnInProtectionState(enabled = false, 0f, 0f)
    }
}
