package com.nexio.tv.core.di

import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.data.catalog.rails.KitsuCatalogRailSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes [KitsuCatalogRailSource] into the multibound `Set<CatalogRailSource>`
 * declared by `CatalogRailSources` in `CatalogRailModule.kt` (Plan 1/2).
 *
 * One file per provider. After Plan 7 lands, all six providers are conformant and
 * Plan 8 can retire the `@CatalogRailNotYetUniform` tracking annotation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class KitsuRailBindings {
    @Binds
    @IntoSet
    abstract fun bindKitsuCatalogRailSource(impl: KitsuCatalogRailSource): CatalogRailSource
}
