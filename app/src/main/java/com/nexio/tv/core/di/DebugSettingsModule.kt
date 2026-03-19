package com.nexio.tv.core.di

import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.debug.passthrough.TransportValidationSettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugSettingsModule {

    @Binds
    @Singleton
    abstract fun bindTransportValidationSettingsStore(
        impl: DebugSettingsDataStore
    ): TransportValidationSettingsStore
}
