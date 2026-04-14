package com.nexio.tv.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager
) : ViewModel() {

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val activeProfileId: StateFlow<Int> = profileManager.activeProfileId

    // Derive PIN-enabled map directly from profiles (no Supabase in Phase 3, per Pitfall 5)
    val profilePinEnabled: StateFlow<Map<Int, Boolean>> = profileManager.profiles
        .map { list -> list.associate { it.id to it.pinEnabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun selectProfile(profileId: Int) {
        viewModelScope.launch {
            profileManager.setActiveProfile(profileId)
        }
    }
}
