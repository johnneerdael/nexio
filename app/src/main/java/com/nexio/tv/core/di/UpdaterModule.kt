package com.nexio.tv.core.di

import com.nexio.tv.BuildConfig
import com.nexio.tv.updater.UpdateChannel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UpdaterModule {

    @Provides
    @Singleton
    fun provideUpdateChannel(): UpdateChannel =
        UpdateChannel.fromBuildConfig(BuildConfig.UPDATE_CHANNEL)
}
