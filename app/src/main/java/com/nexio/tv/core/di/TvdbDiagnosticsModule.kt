package com.nexio.tv.core.di

import com.nexio.tv.core.tvdb.TvdbDiagnosticsRecorder
import com.nexio.tv.data.local.TvdbDiagnosticsDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds [TvdbDiagnosticsRecorder] to its concrete
 * [TvdbDiagnosticsDataStore] implementation.
 *
 * This binding must exist before plans 10-01 through 10-04 add producer
 * injections that depend on the recorder interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TvdbDiagnosticsModule {

    @Binds
    @Singleton
    abstract fun bindTvdbDiagnosticsRecorder(
        store: TvdbDiagnosticsDataStore
    ): TvdbDiagnosticsRecorder
}
