package com.nexio.tv.integrations.hyperhdr.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.hyperHdrDataStore by preferencesDataStore(name = "hyperhdr_config")

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HyperHdrPrefs

@Module
@InstallIn(SingletonComponent::class)
object HyperHdrModule {

    @Provides
    @Singleton
    @HyperHdrPrefs
    fun provideHyperHdrDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.hyperHdrDataStore
}
