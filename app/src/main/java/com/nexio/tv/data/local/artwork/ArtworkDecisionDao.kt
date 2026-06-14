package com.nexio.tv.data.local.artwork

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class ArtworkDecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDecision(entity: ArtworkDecisionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDecisions(entities: List<ArtworkDecisionEntity>)

    @Query("SELECT * FROM artwork_decisions WHERE decisionKey = :decisionKey")
    abstract suspend fun getDecision(decisionKey: String): ArtworkDecisionEntity?

    @Query("SELECT * FROM artwork_decisions")
    abstract suspend fun getAllDecisions(): List<ArtworkDecisionEntity>

    @Query("DELETE FROM artwork_decisions WHERE decisionKey = :decisionKey")
    abstract suspend fun deleteDecision(decisionKey: String): Int

    @Query("DELETE FROM artwork_decisions WHERE settingsHash = :settingsHash")
    abstract suspend fun deleteDecisionsBySettingsHash(settingsHash: String): Int

    @Query("DELETE FROM artwork_decisions WHERE credentialHash = :credentialHash")
    abstract suspend fun deleteDecisionsByCredentialHash(credentialHash: String): Int

    @Query("DELETE FROM artwork_decisions WHERE settingsHash IN (:settingsHashes) OR credentialHash IN (:credentialHashes)")
    abstract suspend fun deleteDecisionsByPolicyHashes(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ): Int

    @Query("DELETE FROM artwork_decisions WHERE settingsHash IS NOT NULL OR credentialHash IS NOT NULL")
    abstract suspend fun deletePremiumScopedDecisions(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPreviewLink(entity: ArtworkPreviewLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPreviewLinks(entities: List<ArtworkPreviewLinkEntity>)

    @Query("SELECT canonicalKey FROM artwork_preview_links WHERE previewKey = :previewKey")
    abstract suspend fun getCanonicalKeyForPreview(previewKey: String): String?

    @Query("SELECT * FROM artwork_preview_links")
    abstract suspend fun getAllPreviewLinks(): List<ArtworkPreviewLinkEntity>

    @Query("DELETE FROM artwork_preview_links WHERE previewKey = :decisionKey OR canonicalKey = :decisionKey")
    abstract suspend fun deleteLinksReferencingDecision(decisionKey: String): Int

    @Query(
        "DELETE FROM artwork_preview_links " +
            "WHERE canonicalKey NOT IN (SELECT decisionKey FROM artwork_decisions)"
    )
    abstract suspend fun deleteLinksReferencingMissingDecisions(): Int

    @Transaction
    open suspend fun deleteDecisionAndLinks(decisionKey: String) {
        deleteDecision(decisionKey)
        deleteLinksReferencingDecision(decisionKey)
    }
}
