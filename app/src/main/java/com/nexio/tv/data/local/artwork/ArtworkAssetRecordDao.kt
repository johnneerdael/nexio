package com.nexio.tv.data.local.artwork

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArtworkAssetRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssetRecord(entity: ArtworkAssetRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssetRecords(entities: List<ArtworkAssetRecordEntity>)

    @Query("SELECT * FROM artwork_asset_records WHERE assetKey = :assetKey")
    suspend fun getAssetRecord(assetKey: String): ArtworkAssetRecordEntity?

    @Query("SELECT * FROM artwork_asset_records")
    suspend fun getAllAssetRecords(): List<ArtworkAssetRecordEntity>

    @Query(
        "SELECT * FROM artwork_asset_records " +
            "WHERE decisionKey = :decisionKey " +
            "ORDER BY fetchedAtMs DESC " +
            "LIMIT 1"
    )
    suspend fun findLatestAssetForDecision(decisionKey: String): ArtworkAssetRecordEntity?

    @Query("SELECT * FROM artwork_asset_records WHERE expiresAtMs <= :nowMs")
    suspend fun findExpired(nowMs: Long): List<ArtworkAssetRecordEntity>
}
