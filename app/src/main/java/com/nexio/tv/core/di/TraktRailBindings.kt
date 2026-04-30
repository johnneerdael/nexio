package com.nexio.tv.core.di

import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.data.catalog.rails.TraktCatalogRailSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes [TraktCatalogRailSource] into the multibound `Set<CatalogRailSource>`
 * declared by `CatalogRailSources` in `CatalogRailModule.kt` (Plan 1/2).
 *
 * One file per provider — keeps each migration self-contained and avoids touching the
 * central registry as Plans 4–7 land.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TraktRailBindings {
    @Binds
    @IntoSet
    abstract fun bindTraktCatalogRailSource(impl: TraktCatalogRailSource): CatalogRailSource
}
