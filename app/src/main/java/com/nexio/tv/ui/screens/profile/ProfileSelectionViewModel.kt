package com.nexio.tv.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.ProfileSyncService
import com.nexio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService
) : ViewModel() {
    private val pinVerificationLock = Any()

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId

    // Derive PIN-enabled map directly from profiles (no Supabase in Phase 3, per Pitfall 5)
    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = profileManager.profiles
        .map { list -> list.associate { it.id to it.pinEnabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // PIN verification state
    private val _pinState = MutableStateFlow(PinVerificationState())
    val pinState: StateFlow<PinVerificationState> = _pinState.asStateFlow()

    fun selectProfile(profileId: Int) {
        viewModelScope.launch {
            profileManager.setActiveProfile(profileId)
        }
    }

    fun verifyPin(profileId: Int, pin: String) {
        if (!beginPinVerification()) return
        viewModelScope.launch {
            val result = profileSyncService.verifyProfilePin(profileId, pin)
            if (result.isFailure) {
                _pinState.update {
                    it.copy(
                        isVerifying = false,
                        isError = true,
                        errorMessage = "Unable to verify PIN"
                    )
                }
                return@launch
            }

            val pinResult = result.getOrThrow()

            if (pinResult.unlocked) {
                resetPinState()
                profileManager.setActiveProfile(profileId)
            } else if (pinResult.retryAfterSeconds > 0) {
                _pinState.update {
                    it.copy(
                        isVerifying = false,
                        isError = false,
                        errorMessage = null,
                        retryAfterSeconds = pinResult.retryAfterSeconds
                    )
                }
                startRateLimitCountdown(pinResult.retryAfterSeconds)
            } else {
                _pinState.update {
                    it.copy(
                        isVerifying = false,
                        isError = true,
                        errorMessage = "Wrong PIN"
                    )
                }
            }
        }
    }

    private fun beginPinVerification(): Boolean = synchronized(pinVerificationLock) {
        val state = _pinState.value
        if (state.isVerifying || state.retryAfterSeconds > 0) return false
        _pinState.value = state.copy(isVerifying = true, isError = false, errorMessage = null)
        true
    }

    private var countdownJob: Job? = null

    private fun startRateLimitCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _pinState.update { it.copy(retryAfterSeconds = remaining) }
                delay(1000)
            }
            _pinState.update { it.copy(retryAfterSeconds = 0) }
        }
    }

    fun consumePinError() {
        _pinState.update { it.copy(isError = false, errorMessage = null) }
    }

    fun resetPinState() {
        countdownJob?.cancel()
        _pinState.value = PinVerificationState()
    }

    /**
     * PIN verification UI state.
     */
    data class PinVerificationState(
        val isVerifying: Boolean = false,
        val isError: Boolean = false,
        val errorMessage: String? = null,
        val retryAfterSeconds: Int = 0
    )
}
