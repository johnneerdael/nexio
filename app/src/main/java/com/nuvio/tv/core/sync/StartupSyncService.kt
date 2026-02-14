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

                // Sync library items
                libraryRepository.isSyncingFromRemote = true
                val remoteLibraryItems = librarySyncService.pullFromRemote()
                Log.d(TAG, "Pulled ${remoteLibraryItems.size} library items from remote")
                if (remoteLibraryItems.isNotEmpty()) {
                    libraryPreferences.mergeRemoteItems(remoteLibraryItems)
                    Log.d(TAG, "Merged ${remoteLibraryItems.size} library items into local")
                }
                libraryRepository.isSyncingFromRemote = false
                librarySyncService.pushToRemote()

                // Sync watched items
                val remoteWatchedItems = watchedItemsSyncService.pullFromRemote()
                Log.d(TAG, "Pulled ${remoteWatchedItems.size} watched items from remote")
                if (remoteWatchedItems.isNotEmpty()) {
                    watchedItemsPreferences.mergeRemoteItems(remoteWatchedItems)
                    Log.d(TAG, "Merged ${remoteWatchedItems.size} watched items into local")
                }
                watchedItemsSyncService.pushToRemote()
            } else {
                Log.d(TAG, "Skipping watch progress & library sync (Trakt connected)")
            }
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            Log.e(TAG, "Startup sync failed", e)
        }
    }
}
