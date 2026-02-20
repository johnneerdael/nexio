package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val pluginSyncService: PluginSyncService,
    private val addonSyncService: AddonSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupPullJob: Job? = null
    private var lastPulledUserId: String? = null
    @Volatile
    private var forceSyncRequested: Boolean = false

    init {
        scope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.Anonymous -> {
                        val force = forceSyncRequested
                        val started = scheduleStartupPull(state.userId, force = force)
                        if (force && started) forceSyncRequested = false
                    }
                    is AuthState.FullAccount -> {
                        val force = forceSyncRequested
                        val started = scheduleStartupPull(state.userId, force = force)
                        if (force && started) forceSyncRequested = false
                    }
                    is AuthState.SignedOut -> {
                        startupPullJob?.cancel()
                        startupPullJob = null
                        lastPulledUserId = null
                        forceSyncRequested = false
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
    }

    fun requestSyncNow() {
        forceSyncRequested = true
        when (val state = authManager.authState.value) {
            is AuthState.Anonymous -> {
                val started = scheduleStartupPull(state.userId, force = true)
                if (started) forceSyncRequested = false
            }
            is AuthState.FullAccount -> {
                val started = scheduleStartupPull(state.userId, force = true)
                if (started) forceSyncRequested = false
            }
            else -> Unit
        }
    }

    private fun scheduleStartupPull(userId: String, force: Boolean = false): Boolean {
        if (!force && lastPulledUserId == userId) return false
        if (force && startupPullJob?.isActive == true) {
            startupPullJob?.cancel()
        } else if (startupPullJob?.isActive == true) {
            return false
        }

        startupPullJob = scope.launch {
            val maxAttempts = 3
            repeat(maxAttempts) { index ->
                val attempt = index + 1
                Log.d(TAG, "Startup sync attempt $attempt/$maxAttempts for user=$userId")
                val result = pullRemoteData()
                if (result.isSuccess) {
                    lastPulledUserId = userId
                    Log.d(TAG, "Startup sync completed for user=$userId")
                    return@launch
                }

                Log.w(TAG, "Startup sync attempt $attempt failed for user=$userId", result.exceptionOrNull())
                if (attempt < maxAttempts) {
                    delay(3000)
                }
            }
        }
        return true
    }

    private suspend fun pullRemoteData(): Result<Unit> {
        try {
            pluginManager.isSyncingFromRemote = true
            val remotePluginUrls = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
            pluginManager.reconcileWithRemoteRepoUrls(
                remoteUrls = remotePluginUrls,
                removeMissingLocal = false
            )
            pluginManager.isSyncingFromRemote = false
            Log.d(TAG, "Pulled ${remotePluginUrls.size} plugin repos from remote")

            addonRepository.isSyncingFromRemote = true
            val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = false
            )
            addonRepository.isSyncingFromRemote = false
            Log.d(TAG, "Pulled ${remoteAddonUrls.size} addons from remote")

            val isTraktConnected = traktAuthDataStore.isAuthenticated.first()
            Log.d(TAG, "Watch progress sync: isTraktConnected=$isTraktConnected")
            if (!isTraktConnected) {
                // Re-check before each pull to avoid stale decisions when Trakt auth flips mid-sync.
                if (traktAuthDataStore.isAuthenticated.first()) {
                    Log.d(TAG, "Skipping watch progress & library sync (Trakt connected during startup sync)")
                    return Result.success(Unit)
                }

                watchProgressRepository.isSyncingFromRemote = true
                val remoteEntries = watchProgressSyncService.pullFromRemote().getOrElse { throw it }
                if (traktAuthDataStore.isAuthenticated.first()) {
                    Log.d(TAG, "Discarding account watch progress pull (Trakt connected during pull)")
                    watchProgressRepository.isSyncingFromRemote = false
                    return Result.success(Unit)
                }
                Log.d(TAG, "Pulled ${remoteEntries.size} watch progress entries from remote")
                watchProgressPreferences.mergeRemoteEntries(remoteEntries.toMap())
                Log.d(TAG, "Merged local watch progress with ${remoteEntries.size} remote entries")
                watchProgressRepository.isSyncingFromRemote = false

                if (traktAuthDataStore.isAuthenticated.first()) {
                    Log.d(TAG, "Skipping library/watch history sync (Trakt connected during startup sync)")
                    return Result.success(Unit)
                }

                libraryRepository.isSyncingFromRemote = true
                val remoteLibraryItems = librarySyncService.pullFromRemote().getOrElse { throw it }
                Log.d(TAG, "Pulled ${remoteLibraryItems.size} library items from remote")
                libraryPreferences.mergeRemoteItems(remoteLibraryItems)
                Log.d(TAG, "Reconciled local library with ${remoteLibraryItems.size} remote items")
                libraryRepository.isSyncingFromRemote = false

                val remoteWatchedItems = watchedItemsSyncService.pullFromRemote().getOrElse { throw it }
                Log.d(TAG, "Pulled ${remoteWatchedItems.size} watched items from remote")
                watchedItemsPreferences.replaceWithRemoteItems(remoteWatchedItems)
                Log.d(TAG, "Reconciled local watched items with ${remoteWatchedItems.size} remote items")
            } else {
                Log.d(TAG, "Skipping watch progress & library sync (Trakt connected)")
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            Log.e(TAG, "Startup sync failed", e)
            return Result.failure(e)
        }
    }
}
