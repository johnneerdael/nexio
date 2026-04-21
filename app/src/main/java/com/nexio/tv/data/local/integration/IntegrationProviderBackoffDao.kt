package com.nexio.tv.data.local.integration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IntegrationProviderBackoffDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IntegrationProviderBackoffEntity)

    @Query(
        """
        SELECT * FROM integration_provider_backoff
        WHERE provider = :provider AND scopeKey = :scopeKey
        LIMIT 1
        """
    )
    suspend fun get(provider: String, scopeKey: String): IntegrationProviderBackoffEntity?
}
