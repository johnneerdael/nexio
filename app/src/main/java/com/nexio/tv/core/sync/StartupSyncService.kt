package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.data.repository.AddonRepositoryImpl
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
    private val addonRepository: AddonRepositoryImpl,
    private val accountSyncRefreshNotifier: AccountSyncRefreshNotifier
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
            authManager.sessionUserId.collect { userId ->
                if (userId != null) {
                    val force = forceSyncRequested
                    val firstAuthForUser = lastAuthenticatedUserId != userId
                    lastAuthenticatedUserId = userId
                    val started = scheduleStartupPull(userId, force = force || firstAuthForUser)
                    if (force && started) forceSyncRequested = false
                } else {
                    startupPullJob?.cancel()
                    startupPullJob = null
                    lastPulledKey = null
                    lastAuthenticatedUserId = null
                    forceSyncRequested = false
                    pendingResyncKey = null
                }
            }
        }
    }

    fun requestSyncNow() {
        forceSyncRequested = true
        authManager.currentSessionUserId?.let { userId ->
            val started = scheduleStartupPull(userId, force = true)
            if (started) forceSyncRequested = false
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
                    return@launch
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
        addonRepository.beginRemoteSyncReconcile()
        return try {
            val remoteAddonConfigs = accountSettingsSyncService.pullFromRemoteAndApply().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonConfigs(
                remoteAddons = remoteAddonConfigs,
                removeMissingLocal = true
            )
            accountSyncRefreshNotifier.notifyRefreshRequired()
            Log.d(TAG, "Pulled account snapshot with ${remoteAddonConfigs.size} addons from remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Startup account snapshot sync failed", e)
            Result.failure(e)
        } finally {
            addonRepository.endRemoteSyncReconcile()
        }
    }
}
