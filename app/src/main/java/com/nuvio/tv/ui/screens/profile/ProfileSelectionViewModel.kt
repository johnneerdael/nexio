package com.nuvio.tv.ui.screens.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileSelectionViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileSyncService: ProfileSyncService,
    private val avatarRepository: AvatarRepository
) : ViewModel() {

    val profiles: StateFlow<List<UserProfile>> = profileManager.profiles

    val canAddProfile: Boolean
        get() = profileManager.profiles.value.size < 4

    private val _avatarCatalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    val avatarCatalog: StateFlow<List<AvatarCatalogItem>> = _avatarCatalog.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    fun loadAvatarCatalog() {
        viewModelScope.launch {
            try {
                _avatarCatalog.value = avatarRepository.getAvatarCatalog()
            } catch (e: Exception) {
                Log.e("ProfileSelectionVM", "Failed to load avatar catalog", e)
            }
        }
    }

    fun getAvatarImageUrl(avatarId: String?): String? {
        if (avatarId == null) return null
        return avatarRepository.getAvatarImageUrl(avatarId, _avatarCatalog.value)
    }

    fun selectProfile(id: Int, onComplete: () -> Unit) {
        viewModelScope.launch {
            profileManager.setActiveProfile(id)
            onComplete()
        }
    }

    fun createProfile(
        name: String,
        avatarColorHex: String,
        avatarId: String? = null
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            val success = profileManager.createProfile(
                name = name,
                avatarColorHex = avatarColorHex,
                avatarId = avatarId
            )
            if (success) {
                profileSyncService.pushToRemote()
            }
            _isCreating.value = false
        }
    }
}
