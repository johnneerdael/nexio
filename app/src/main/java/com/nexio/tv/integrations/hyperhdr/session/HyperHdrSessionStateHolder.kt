package com.nexio.tv.integrations.hyperhdr.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton — written by the player lifecycle wiring (PlayerRuntimeControllerInitialization),
 * read by the player UI (PlayerScreen.kt's badge Row). Decouples the badge composition from the
 * lifecycle code so neither has to know about the other directly.
 */
@Singleton
class HyperHdrSessionStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<HyperHdrSessionState>(HyperHdrSessionState.Idle)
    val state: StateFlow<HyperHdrSessionState> = _state.asStateFlow()

    fun update(next: HyperHdrSessionState) {
        _state.value = next
    }
}
