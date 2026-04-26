package com.nexio.tv.data.local.integration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class MediaIdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMediaIdentity(entity: MediaIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertExternalIds(entities: List<ExternalIdEntity>)

    @Query("SELECT * FROM integration_media_identity WHERE mediaKey = :mediaKey")
    abstract suspend fun getMediaIdentity(mediaKey: String): MediaIdentityEntity?

    @Query("SELECT * FROM integration_external_id WHERE mediaKey = :mediaKey ORDER BY provider ASC, idType ASC")
    abstract suspend fun externalIdsForMedia(mediaKey: String): List<ExternalIdEntity>

    @Transaction
    open suspend fun replaceExternalIds(mediaKey: String, entities: List<ExternalIdEntity>) {
        deleteExternalIdsForMedia(mediaKey)
        if (entities.isNotEmpty()) {
            upsertExternalIds(entities)
        }
    }

    @Query("DELETE FROM integration_external_id WHERE mediaKey = :mediaKey")
    protected abstract suspend fun deleteExternalIdsForMedia(mediaKey: String)
}
