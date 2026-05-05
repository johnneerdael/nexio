package com.nexio.tv.core.di

import com.google.gson.Gson
import com.nexio.tv.ui.screens.home.order.HomeRailOrderDiagnosticsSink
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStateCodec
import com.nexio.tv.ui.screens.home.order.LoggingHomeRailOrderDiagnosticsSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HomeRailOrderModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideHomeRailOrderStateCodec(gson: Gson): HomeRailOrderStateCodec =
        HomeRailOrderStateCodec(gson)

    @Provides
    @Singleton
    fun provideHomeRailOrderDiagnosticsSink(
        impl: LoggingHomeRailOrderDiagnosticsSink,
    ): HomeRailOrderDiagnosticsSink = impl
}
