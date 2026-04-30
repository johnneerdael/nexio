package com.nexio.tv.core.di

import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.data.catalog.rails.AddonCatalogRailSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Hilt wiring for the catalog-rails uniform contract.
 *
 * - [CatalogRailSources] declares the empty-default multibound set so consumers can inject
 *   `Set<@JvmSuppressWildcards CatalogRailSource>` safely while providers are being migrated
 *   plan-by-plan.
 * - [AddonRailBindings] contributes [AddonCatalogRailSource] into the set (Plan 2).
 *
 * Plans 3–7 each add another `@Module @InstallIn(SingletonComponent::class)` companion that
 * `@Binds @IntoSet` its provider's `CatalogRailSource` impl. No central registry edit needed.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogRailSources {
    @Multibinds
    abstract fun bindCatalogRailSourceSet(): Set<CatalogRailSource>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AddonRailBindings {
    @Binds
    @IntoSet
    abstract fun bindAddonCatalogRailSource(impl: AddonCatalogRailSource): CatalogRailSource
}
