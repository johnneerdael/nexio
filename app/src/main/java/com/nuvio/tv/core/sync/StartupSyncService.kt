package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val watchProgressPreferences: WatchProgressPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            val state = authManager.authState.first { it !is AuthState.Loading }
            if (state is AuthState.Anonymous || state is AuthState.FullAccount) {
                pullRemoteData()
            }
        }
    }

    private suspend fun pullRemoteData() {
        try {
            pluginManager.isSyncingFromRemote = true
            val newPluginUrls = pluginSyncService.getNewRemoteRepoUrls()
            for (url in newPluginUrls) {
                pluginManager.addRepository(url)
            }
            pluginManager.isSyncingFromRemote = false
            Log.d(TAG, "Pulled ${newPluginUrls.size} new plugin repos from remote")

            addonRepository.isSyncingFromRemote = true
            val newAddonUrls = addonSyncService.getNewRemoteAddonUrls()
            for (url in newAddonUrls) {
                addonRepository.addAddon(url)
            }
            addonRepository.isSyncingFromRemote = false
            Log.d(TAG, "Pulled ${newAddonUrls.size} new addons from remote")

            // Sync watch progress only if Trakt is NOT connected
            val isTraktConnected = traktAuthDataStore.isAuthenticated.first()
            Log.d(TAG, "Watch progress sync: isTraktConnected=$isTraktConnected")
            if (!isTraktConnected) {
                watchProgressRepository.isSyncingFromRemote = true
                val remoteEntries = watchProgressSyncService.pullFromRemote()
                Log.d(TAG, "Pulled ${remoteEntries.size} watch progress entries from remote")
                if (remoteEntries.isNotEmpty()) {
                    watchProgressPreferences.mergeRemoteEntries(remoteEntries.toMap())
                    Log.d(TAG, "Merged ${remoteEntries.size} watch progress entries into local")
                } else {
                    Log.d(TAG, "No remote watch progress entries to merge")
                }
                watchProgressRepository.isSyncingFromRemote = false

                // Push local watch progress so linked devices can pull it
                Log.d(TAG, "Pushing local watch progress to remote")
                watchProgressSyncService.pushToRemote()
            } else {
                Log.d(TAG, "Skipping watch progress sync (Trakt connected)")
            }
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            Log.e(TAG, "Startup sync failed", e)
        }
    }
}
