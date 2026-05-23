package com.nexio.tv.core.di

import com.nexio.tv.data.trailer.potoken.PoTokenProvider
import com.nexio.tv.data.trailer.potoken.PoTokenProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class TrailerPoTokenModule {
    @Binds
    abstract fun bindPoTokenProvider(impl: PoTokenProviderImpl): PoTokenProvider
}
