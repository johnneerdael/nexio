package com.nexio.tv.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MDBListSettingsReaderModule {
    @Binds
    @Singleton
    abstract fun bindMDBListSettingsReader(
        impl: DataStoreMDBListSettingsReader,
    ): MDBListSettingsReader
}
