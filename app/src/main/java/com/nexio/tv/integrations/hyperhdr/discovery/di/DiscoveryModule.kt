package com.nexio.tv.integrations.hyperhdr.discovery.di

import android.content.Context
import android.net.nsd.NsdManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiscoveryModule {

    @Provides
    @Singleton
    fun provideNsdManager(@ApplicationContext context: Context): NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
}
