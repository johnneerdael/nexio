package com.nexio.tv.core.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProfileModule {
    // ProfileDataStore, ProfileDataStoreFactory, and ProfileManager use
    // @Singleton + @Inject constructors — Hilt provides them automatically.

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder().create()
}
