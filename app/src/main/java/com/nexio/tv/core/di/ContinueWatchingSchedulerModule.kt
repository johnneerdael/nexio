package com.nexio.tv.core.di

import android.app.AlarmManager
import android.content.Context
import com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmScheduler
import com.nexio.tv.core.scheduler.ContinueWatchingAirScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ContinueWatchingAlarmManagerModule {
    @Provides
    @Singleton
    fun provideAlarmManager(@ApplicationContext context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ContinueWatchingSchedulerBindingModule {
    @Binds
    @Singleton
    abstract fun bindContinueWatchingAirScheduler(
        impl: ContinueWatchingAirAlarmScheduler
    ): ContinueWatchingAirScheduler
}
