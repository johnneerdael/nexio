package com.nexio.tv.data.local.artwork

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ArtworkDecisionEntity::class,
        ArtworkPreviewLinkEntity::class,
        ArtworkAssetRecordEntity::class,
        ArtworkCacheMigrationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ArtworkCacheDatabase : RoomDatabase() {
    abstract fun decisionDao(): ArtworkDecisionDao
    abstract fun assetRecordDao(): ArtworkAssetRecordDao
    abstract fun migrationDao(): ArtworkCacheMigrationDao
}
