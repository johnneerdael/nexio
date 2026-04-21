package com.nexio.tv.data.local.integration

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        IntegrationCacheEntity::class,
        IntegrationOwnerEntity::class,
        IntegrationProviderBackoffEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class IntegrationCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): IntegrationCacheDao
    abstract fun backoffDao(): IntegrationProviderBackoffDao
}
