package com.nexio.tv.data.local.integration

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IntegrationCacheEntity::class,
        IntegrationOwnerEntity::class,
        IntegrationProviderBackoffEntity::class,
        RailCacheEntity::class,
        RailItemEntity::class,
        RailItemPreviewEntity::class,
        MediaIdentityEntity::class,
        ExternalIdEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class IntegrationCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): IntegrationCacheDao
    abstract fun backoffDao(): IntegrationProviderBackoffDao
    abstract fun railStoreDao(): RailStoreDao
    abstract fun mediaIdentityDao(): MediaIdentityDao
}
