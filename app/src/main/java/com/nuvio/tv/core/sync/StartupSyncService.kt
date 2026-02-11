package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.repository.AddonRepositoryImpl
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
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl
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
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            Log.e(TAG, "Startup sync failed", e)
        }
    }
}
