package com.nexio.tv.core.sync

import android.content.Context
import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.auth.hasLiveFullAccountSyncSession
import com.nexio.tv.core.auth.liveFullAccountSessionUserId
import com.nexio.tv.core.profile.ProfileModeRoute
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.repository.AddonRepositoryImpl
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val accountSettingsSyncService: AccountSettingsSyncService,
    private val profileSyncService: ProfileSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val profileWebSyncService: ProfileWebSyncService,
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
    private val addonRepository: AddonRepositoryImpl,
    private val accountSyncRefreshNotifier: AccountSyncRefreshNotifier,
    private val postgrest: Postgrest,
    @ApplicationContext private val context: Context
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
            combine(authManager.authState, authManager.sessionUserId) { authState, sessionUserId ->
                authState to sessionUserId
            }.collect { (authState, sessionUserId) ->
                val userId = liveFullAccountSessionUserId(authState, sessionUserId)
                accountSettingsSyncService.onStartupSyncUserChanged(userId)
                if (userId != null) {
                    val force = forceSyncRequested
                    val firstAuthForUser = lastAuthenticatedUserId != userId
                    lastAuthenticatedUserId = userId
                    val started = scheduleStartupPull(userId, force = force || firstAuthForUser)
                    if (force && started) forceSyncRequested = false
                } else {
                    startupPullJob?.cancel()
                    startupPullJob = null
                    profileSettingsSyncService.stopObserving()
                    lastPulledKey = null
                    lastAuthenticatedUserId = null
                    forceSyncRequested = false
                    pendingResyncKey = null
                }
            }
        }
        scope.launch {
            profileManager.profileSwitched.collect { activeId ->
                if (!hasLiveFullAccountSyncSession(authManager.authState.value, authManager.currentSessionUserId)) {
                    Log.d(TAG, "Skipping secondary profile sync for profile $activeId without a full account session")
                    return@collect
                }
                when (val route = profileModeRouter.routeFor(activeId)) {
                    ProfileModeRoute.DefaultLegacyRoute -> {
                        Log.d(TAG, "Skipping secondary profile sync for default legacy profile $activeId")
                    }
                    is ProfileModeRoute.InvalidProfileRoute -> {
                        Log.w(TAG, "Skipping secondary profile sync for invalid profile ${route.profileId}")
                    }
                    is ProfileModeRoute.SecondaryProfileRoute -> {
                        val blobPullResult = profileSettingsSyncService.pullBlobForProfile(route.profileId)
                        if (blobPullResult.isSuccess) {
                            Log.d(TAG, "Profile settings blob pull succeeded for switched profile ${route.profileId}")
                        } else {
                            Log.w(TAG, "Profile settings blob pull failed for switched profile ${route.profileId}", blobPullResult.exceptionOrNull())
                        }
                        val result = profileWebSyncService.syncActiveProfile(route.profileId)
                        if (result.isSuccess) {
                            Log.d(TAG, "Profile web token sync succeeded for switched profile ${route.profileId}")
                        } else {
                            Log.w(TAG, "Profile web token sync failed for switched profile ${route.profileId}", result.exceptionOrNull())
                        }
                    }
                }
            }
        }
    }

    fun requestSyncNow() {
        if (!hasLiveFullAccountSyncSession(authManager.authState.value, authManager.currentSessionUserId)) {
            Log.d(TAG, "Ignoring startup sync request without a full account session")
            return
        }
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
                val result = pullRemoteProfileState()
                    .fold(
                        onSuccess = { pullRemoteSnapshot() },
                        onFailure = { profileError ->
                            Log.w(TAG, "Startup profile sync failed before account snapshot sync", profileError)
                            pullRemoteSnapshot()
                        }
                    )
                if (result.isSuccess) {
                    lastPulledKey = key
                    accountSettingsSyncService.markStartupRemotePullSucceeded(userId)
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

    private suspend fun pullRemoteProfileState(): Result<Unit> {
        retryPendingRemoteCleanup()

        val profilePullResult = profileSyncService.pullFromRemote()
        if (profilePullResult.isSuccess) {
            Log.d(TAG, "Profile metadata pull succeeded")
        } else {
            Log.w(TAG, "Profile metadata pull failed", profilePullResult.exceptionOrNull())
        }

        val activeId = profileManager.activeProfileId.value
        val (blobPullResult, webTokenPullResult) = when (val route = profileModeRouter.routeFor(activeId)) {
            ProfileModeRoute.DefaultLegacyRoute -> {
                Log.d(TAG, "Skipping secondary profile startup sync for default legacy profile $activeId")
                Result.success(Unit) to Result.success(Unit)
            }
            is ProfileModeRoute.InvalidProfileRoute -> {
                val failure = Result.failure<Unit>(IllegalArgumentException("Invalid profile id ${route.profileId}"))
                failure to failure
            }
            is ProfileModeRoute.SecondaryProfileRoute -> {
                val blobResult = profileSettingsSyncService.pullBlobForProfile(route.profileId)
                if (blobResult.isSuccess) {
                    Log.d(TAG, "Profile settings blob pull succeeded for profile ${route.profileId}")
                } else {
                    Log.w(TAG, "Profile settings blob pull failed for profile ${route.profileId}", blobResult.exceptionOrNull())
                }
                val tokenResult = profileWebSyncService.syncActiveProfile(route.profileId)
                if (tokenResult.isSuccess) {
                    Log.d(TAG, "Profile web token pull succeeded for profile ${route.profileId}")
                } else {
                    Log.w(TAG, "Profile web token pull failed for profile ${route.profileId}", tokenResult.exceptionOrNull())
                }
                blobResult to tokenResult
            }
        }

        profileSettingsSyncService.startObserving()
        return if (profilePullResult.isSuccess && blobPullResult.isSuccess && webTokenPullResult.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(
                profilePullResult.exceptionOrNull()
                    ?: blobPullResult.exceptionOrNull()
                    ?: webTokenPullResult.exceptionOrNull()
                    ?: IllegalStateException("Startup profile sync failed")
            )
        }
    }

    private suspend fun retryPendingRemoteCleanup() {
        val prefs = context.getSharedPreferences("profile_cleanup_state", Context.MODE_PRIVATE)
        val pendingIds = prefs.getStringSet("pending_remote_cleanup", emptySet()) ?: emptySet()
        if (pendingIds.isEmpty()) return

        val remaining = pendingIds.toMutableSet()
        for (idStr in pendingIds) {
            val profileId = idStr.toIntOrNull()
            if (profileId == null) {
                remaining.remove(idStr)
                continue
            }
            try {
                withJwtRefreshRetry {
                    postgrest.rpc(
                        "sync_delete_profile",
                        buildJsonObject {
                            put("p_profile_id", profileId)
                        }
                    )
                }
                remaining.remove(idStr)
                Log.d(TAG, "Remote cleanup succeeded for profile $profileId")
            } catch (e: Exception) {
                Log.w(TAG, "Remote cleanup retry failed for profile $profileId", e)
            }
        }
        prefs.edit().putStringSet("pending_remote_cleanup", remaining.take(4).toSet()).apply()
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

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }
}
