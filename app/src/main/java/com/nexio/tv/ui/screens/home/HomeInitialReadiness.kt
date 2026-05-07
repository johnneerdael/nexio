package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HomeLayout

// This focused fix gates only Continue Watching. Add catalog/profile gates later
// when each gate is wired to real producer resolution events.
enum class HomeInitialGate {
    CONTINUE_WATCHING
}

sealed interface GateStatus {
    data object Pending : GateStatus
    data object Loading : GateStatus
    data class Resolved(val reason: String) : GateStatus
    data class FailedNonBlocking(val reason: String) : GateStatus
}

data class HomeInitialReadiness(
    val sessionId: String,
    val profileId: Int,
    val gates: Map<HomeInitialGate, GateStatus>
) {
    fun markLoading(gate: HomeInitialGate): HomeInitialReadiness =
        copy(gates = gates + (gate to GateStatus.Loading))

    fun markResolved(gate: HomeInitialGate, reason: String): HomeInitialReadiness =
        copy(gates = gates + (gate to GateStatus.Resolved(reason)))

    fun markFailedNonBlocking(gate: HomeInitialGate, reason: String): HomeInitialReadiness =
        copy(gates = gates + (gate to GateStatus.FailedNonBlocking(reason)))

    fun isResolved(gate: HomeInitialGate): Boolean {
        return when (gates[gate]) {
            is GateStatus.Resolved,
            is GateStatus.FailedNonBlocking -> true
            GateStatus.Pending,
            GateStatus.Loading,
            null -> false
        }
    }

    companion object {
        fun started(sessionId: String, profileId: Int): HomeInitialReadiness =
            HomeInitialReadiness(
                sessionId = sessionId,
                profileId = profileId,
                gates = mapOf(
                    HomeInitialGate.CONTINUE_WATCHING to GateStatus.Loading
                )
            )
    }
}

fun shouldShowFullHomeLoadingGate(
    uiState: HomeUiState,
    startupContentGateTimedOut: Boolean
): Boolean {
    if (uiState.error != null || startupContentGateTimedOut) return false
    val hasRenderableContent = hasRenderableHomeContent(uiState)
    if (!hasRenderableContent) {
        return uiState.isLoading ||
            (
                uiState.homeLayout == HomeLayout.MODERN &&
                    uiState.installedAddonsCount > 0 &&
                    !uiState.homeReadiness.isResolved(HomeInitialGate.CONTINUE_WATCHING)
            )
    }
    return false
}
