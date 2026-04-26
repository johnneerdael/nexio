package com.nexio.tv.core.di

import com.nexio.tv.data.trakt.outbox.TraktMutationAdapter
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxPolicy
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxStore
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TraktMutationOutboxBindingsModule {

    @Multibinds
    abstract fun bindTraktMutationAdapters(): Set<TraktMutationAdapter>
}

@Module
@InstallIn(SingletonComponent::class)
object TraktMutationOutboxModule {

    @Provides
    @Singleton
    fun provideTraktMutationOutboxPolicy(): TraktMutationOutboxPolicy {
        return TraktMutationOutboxPolicy()
    }

    @Provides
    @Singleton
    fun provideTraktMutationOutboxWorker(
        store: TraktMutationOutboxStore,
        policy: TraktMutationOutboxPolicy
    ): TraktMutationOutboxWorker {
        return TraktMutationOutboxWorker(
            store = store,
            policy = policy
        )
    }

    @Provides
    @Singleton
    fun provideProviderMutationOutboxCoordinator(
        worker: TraktMutationOutboxWorker,
        adapters: Set<@JvmSuppressWildcards TraktMutationAdapter>
    ): ProviderMutationOutboxCoordinator {
        return ProviderMutationOutboxCoordinator(
            worker = worker,
            adapters = adapters
        )
    }
}
