package com.nexio.tv.di

import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.plugin.PluginManager
import com.nexio.tv.core.plugin.PluginRuntime
import com.nexio.tv.core.sync.PluginSyncService
import com.nexio.tv.data.local.PluginDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun providePluginRuntime(): PluginRuntime {
        return PluginRuntime()
    }

    @Provides
    @Singleton
    fun providePluginManager(
        dataStore: PluginDataStore,
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager
    ): PluginManager {
        return PluginManager(dataStore, runtime, pluginSyncService, authManager)
    }
}
