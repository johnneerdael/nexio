package com.nexio.tv.core.di

import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.data.catalog.rails.MDBListCatalogRailSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes [MDBListCatalogRailSource] into the multibound `Set<CatalogRailSource>`
 * declared by `CatalogRailSources` in `CatalogRailModule.kt` (Plan 1/2).
 *
 * One file per provider — keeps each migration self-contained and avoids touching the
 * central registry as Plans 5–7 land.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MDBListRailBindings {
    @Binds
    @IntoSet
    abstract fun bindMDBListCatalogRailSource(impl: MDBListCatalogRailSource): CatalogRailSource
}
