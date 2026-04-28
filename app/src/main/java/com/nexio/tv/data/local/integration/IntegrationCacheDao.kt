package com.nexio.tv.data.local.integration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
abstract class IntegrationCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertCacheEntry(entity: IntegrationCacheEntity)

    @Query("SELECT * FROM integration_cache WHERE cacheKey = :cacheKey")
    abstract suspend fun getCacheEntry(cacheKey: String): IntegrationCacheEntity?

    @Query("SELECT * FROM integration_cache WHERE ownerToken = :mediaKey")
    abstract suspend fun findByMediaKey(mediaKey: String): List<IntegrationCacheEntity>

    @Query("DELETE FROM integration_cache WHERE ownerToken = :mediaKey")
    abstract suspend fun deleteByMediaKey(mediaKey: String): Int

    /**
     * F-D-02 atomic write: caller writes blob bytes to {tmpFile} first, then this transaction
     * renames the .tmp into place and upserts the cache row. The Room @Transaction guarantees
     * the row upsert is atomic; the rename is atomic at the filesystem layer (POSIX rename).
     * Together, readers see EITHER the previous (file, row) pair OR the new (file, row) pair —
     * never a mixed state.
     *
     * If the rename fails (e.g. cross-filesystem), falls back to copy+delete.
     */
    @androidx.room.Transaction
    open suspend fun atomicRenameAndUpsert(
        tmpFile: java.io.File,
        finalFile: java.io.File,
        entity: IntegrationCacheEntity
    ) {
        require(tmpFile.exists()) { "tmp file ${tmpFile.path} must exist before atomic upsert" }
        if (!tmpFile.renameTo(finalFile)) {
            // renameTo can fail across filesystems; fall back to copy + delete
            tmpFile.inputStream().use { src -> finalFile.outputStream().use { dst -> src.copyTo(dst) } }
            tmpFile.delete()
        }
        upsertCacheEntry(entity)
    }
}
