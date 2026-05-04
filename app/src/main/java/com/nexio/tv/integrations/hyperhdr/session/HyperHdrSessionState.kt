package com.nexio.tv.integrations.hyperhdr.session

import com.nexio.tv.integrations.hyperhdr.capture.CaptureMode

/**
 * Snapshot of the HyperHDR ambilight session for UI surfaces (player-overlay badge,
 * future status displays). [Idle] means no session is active. [Connecting] / [Connected]
 * / [Reconnecting] reflect the underlying FlatBuffer client's state.
 */
sealed interface HyperHdrSessionState {
    data object Idle : HyperHdrSessionState
    data class Connecting(val mode: CaptureMode) : HyperHdrSessionState
    data class Connected(val mode: CaptureMode) : HyperHdrSessionState
    data class Reconnecting(val mode: CaptureMode) : HyperHdrSessionState
}
