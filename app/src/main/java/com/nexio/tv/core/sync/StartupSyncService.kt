package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.data.repository.AddonRepositoryImpl
import com.nexio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val accountSettingsSyncService: AccountSettingsSyncService,
    private val addonRepository: AddonRepositoryImpl
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupPullJob: Job? = null
    private var lastPulledKey: String? = null
    private var lastAuthenticatedUserId: String? = null
    @Volatile
    private var forceSyncRequested: Boolean = false
    @Volatile
    private var pendingResyncKey: String? = null

    init {
        scope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.FullAccount -> {
                        val force = forceSyncRequested
                        val firstAuthForUser = lastAuthenticatedUserId != state.userId
                        lastAuthenticatedUserId = state.userId
                        val started = scheduleStartupPull(state.userId, force = force || firstAuthForUser)
                        if (force && started) forceSyncRequested = false
                    }

                    is AuthState.SignedOut -> {
                        startupPullJob?.cancel()
                        startupPullJob = null
                        lastPulledKey = null
                        lastAuthenticatedUserId = null
                        forceSyncRequested = false
                        pendingResyncKey = null
                    }

                    is AuthState.Loading -> Unit
                }
            }
        }
    }

    fun requestSyncNow() {
        forceSyncRequested = true
        when (val state = authManager.authState.value) {
            is AuthState.FullAccount -> {
                val started = scheduleStartupPull(state.userId, force = true)
                if (started) forceSyncRequested = false
            }

            else -> Unit
        }
    }

    private fun pullKey(userId: String): String = "${userId}_addons"

    private fun scheduleStartupPull(userId: String, force: Boolean = false): Boolean {
        val key = pullKey(userId)
        if (!force && lastPulledKey == key) return false
        if (startupPullJob?.isActive == true) {
            if (force) pendingResyncKey = key
            return false
        }

        startupPullJob = scope.launch {
            val maxAttempts = 3
            var syncCompleted = false
            repeat(maxAttempts) { index ->
                val attempt = index + 1
                Log.d(TAG, "Startup account snapshot sync attempt $attempt/$maxAttempts for key=$key")
                val result = pullRemoteSnapshot()
                if (result.isSuccess) {
                    lastPulledKey = key
                    syncCompleted = true
                    return@repeat
                }
                Log.w(TAG, "Startup account snapshot sync attempt $attempt failed for key=$key", result.exceptionOrNull())
                if (attempt < maxAttempts) delay(3000)
            }

            val resyncKey = pendingResyncKey
            if (resyncKey != null) {
                pendingResyncKey = null
                if (!syncCompleted || resyncKey != lastPulledKey) {
                    scheduleStartupPull(userId, force = true)
                }
            }
        }
        return true
    }

    private suspend fun pullRemoteSnapshot(): Result<Unit> {
        return try {
            addonRepository.isSyncingFromRemote = true
            val remoteAddonUrls = accountSettingsSyncService.pullFromRemoteAndApply().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = true
            )
            Log.d(TAG, "Pulled account snapshot with ${remoteAddonUrls.size} addons from remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Startup account snapshot sync failed", e)
            Result.failure(e)
        } finally {
            addonRepository.isSyncingFromRemote = false
        }
    }
}
