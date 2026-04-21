package com.nexio.tv.domain.model

sealed class AuthState {
    data object SignedOut : AuthState()
    data object Loading : AuthState()
    // Returning user whose Supabase session could not be restored on cold
    // start. Distinct from SignedOut so the UI can offer a "reconnect" nudge
    // instead of the first-run sign-in pitch.
    data object SessionLost : AuthState()
    data class FullAccount(val userId: String, val email: String) : AuthState()
}
