package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.auth.hasLiveFullAccountSyncSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.AccountSettingsSyncService
import com.nexio.tv.core.sync.AccountSyncRefreshNotifier
import com.nexio.tv.core.sync.ProfileSettingsSyncService
import com.nexio.tv.core.sync.ProfileSyncService
import com.nexio.tv.data.repository.AddonRepositoryImpl
import com.nexio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal enum class SyncStatus { IDLE, SYNCING, SUCCESS, ERROR }

@HiltViewModel
internal class SettingsViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val profileSyncService: ProfileSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val accountSettingsSyncService: AccountSettingsSyncService,
    private val addonRepository: AddonRepositoryImpl,
    private val accountSyncRefreshNotifier: AccountSyncRefreshNotifier,
    private val profileManager: ProfileManager
) : ViewModel() {
    val hasLiveFullAccountSession: StateFlow<Boolean> = combine(
        authManager.authState,
        authManager.sessionUserId
    ) { authState, sessionUserId ->
        hasLiveFullAccountSyncSession(authState, sessionUserId)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        hasLiveFullAccountSyncSession(authManager.authState.value, authManager.currentSessionUserId)
    )

    val isPrimaryProfile: StateFlow<Boolean> = profileManager.activeProfileId
        .map { it == 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val activeProfile: StateFlow<UserProfile?> = combine(
        profileManager.profiles,
        profileManager.activeProfileId
    ) { profiles, activeId ->
        profiles.find { it.id == activeId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _showDeleteDialog = MutableStateFlow<UserProfile?>(null)
    val showDeleteDialog: StateFlow<UserProfile?> = _showDeleteDialog.asStateFlow()

    private val _deleteInProgress = MutableStateFlow(false)
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress.asStateFlow()

    fun triggerSyncNow() {
        if (!hasLiveFullAccountSyncSession(authManager.authState.value, authManager.currentSessionUserId)) return
        if (_syncStatus.value == SyncStatus.SYNCING) return
        viewModelScope.launch {
            _syncStatus.value = SyncStatus.SYNCING
            val accountResult = pullAccountSnapshot()
            val pullResult = profileSyncService.pullFromRemote()
            val activeId = profileManager.activeProfileId.value
            val metaResult = if (pullResult.isSuccess) {
                profileSyncService.pushToRemote()
            } else {
                pullResult.map { Unit }
            }
            val blobResult = profileSettingsSyncService.pushBlobForProfile(activeId)
            _syncStatus.value = if (accountResult.isSuccess && metaResult.isSuccess && blobResult.isSuccess) {
                SyncStatus.SUCCESS
            } else {
                SyncStatus.ERROR
            }
            delay(3000)
            _syncStatus.value = SyncStatus.IDLE
        }
    }

    private suspend fun pullAccountSnapshot(): Result<Unit> {
        addonRepository.beginRemoteSyncReconcile()
        return try {
            val remoteAddonConfigs = accountSettingsSyncService.pullFromRemoteAndApply().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonConfigs(
                remoteAddons = remoteAddonConfigs,
                removeMissingLocal = true
            )
            accountSyncRefreshNotifier.notifyRefreshRequired()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            addonRepository.endRemoteSyncReconcile()
        }
    }

    fun requestDeleteProfile(profile: UserProfile) {
        if (profile.id == 1) return
        _showDeleteDialog.value = profile
    }

    fun dismissDeleteDialog() {
        if (_deleteInProgress.value) return
        _showDeleteDialog.value = null
    }

    fun confirmDeleteProfile() {
        val profile = _showDeleteDialog.value ?: return
        if (_deleteInProgress.value) return
        val syncRemoteDelete = hasLiveFullAccountSyncSession(
            authManager.authState.value,
            authManager.currentSessionUserId
        )
        viewModelScope.launch {
            _deleteInProgress.value = true
            try {
                profileManager.deleteProfile(
                    profile.id,
                    syncRemoteDelete = syncRemoteDelete
                )
                _showDeleteDialog.value = null
            } finally {
                _deleteInProgress.value = false
            }
        }
    }
}
