package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.AutoplayBandwidthMode

internal fun AutoplayBandwidthMode.effectiveAutoplayBandwidthMode(
    autoplayBenchmarkAvailable: Boolean
): AutoplayBandwidthMode {
    return if (this == AutoplayBandwidthMode.AUTO && !autoplayBenchmarkAvailable) {
        AutoplayBandwidthMode.MANUAL
    } else {
        this
    }
}

internal fun AutoplayBandwidthMode.isDeterministicAutoplayAvailable(
    autoplayBenchmarkAvailable: Boolean
): Boolean {
    val effectiveBandwidthMode = effectiveAutoplayBandwidthMode(autoplayBenchmarkAvailable)
    return effectiveBandwidthMode == AutoplayBandwidthMode.MANUAL || autoplayBenchmarkAvailable
}
