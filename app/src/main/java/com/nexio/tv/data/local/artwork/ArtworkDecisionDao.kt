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

    @Transaction
    open suspend fun deleteDecisionsBySettingsHash(settingsHash: String): Int =
        deleteDecisionsAndLinks(decisionKeysBySettingsHash(settingsHash))

    @Transaction
    open suspend fun deleteDecisionsByCredentialHash(credentialHash: String): Int =
        deleteDecisionsAndLinks(decisionKeysByCredentialHash(credentialHash))

    @Transaction
    open suspend fun deleteDecisionsByPolicyHashes(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ): Int {
        val decisionKeys = linkedSetOf<String>()
        if (settingsHashes.isNotEmpty()) {
            decisionKeys += decisionKeysBySettingsHashes(settingsHashes)
        }
        if (credentialHashes.isNotEmpty()) {
            decisionKeys += decisionKeysByCredentialHashes(credentialHashes)
        }
        return deleteDecisionsAndLinks(decisionKeys.toList())
    }

    @Transaction
    open suspend fun deletePremiumScopedDecisions(): Int =
        deleteDecisionsAndLinks(premiumScopedDecisionKeys())

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
        deleteLinksReferencingDecision(decisionKey)
        deleteDecision(decisionKey)
    }

    private suspend fun deleteDecisionsAndLinks(decisionKeys: List<String>): Int {
        if (decisionKeys.isEmpty()) return 0
        deleteLinksReferencingDecisions(decisionKeys)
        return deleteDecisionsByKeys(decisionKeys)
    }

    @Query("SELECT decisionKey FROM artwork_decisions WHERE settingsHash = :settingsHash")
    protected abstract suspend fun decisionKeysBySettingsHash(settingsHash: String): List<String>

    @Query("SELECT decisionKey FROM artwork_decisions WHERE credentialHash = :credentialHash")
    protected abstract suspend fun decisionKeysByCredentialHash(credentialHash: String): List<String>

    @Query("SELECT decisionKey FROM artwork_decisions WHERE settingsHash IN (:settingsHashes)")
    protected abstract suspend fun decisionKeysBySettingsHashes(settingsHashes: Set<String>): List<String>

    @Query("SELECT decisionKey FROM artwork_decisions WHERE credentialHash IN (:credentialHashes)")
    protected abstract suspend fun decisionKeysByCredentialHashes(credentialHashes: Set<String>): List<String>

    @Query("SELECT decisionKey FROM artwork_decisions WHERE settingsHash IS NOT NULL OR credentialHash IS NOT NULL")
    protected abstract suspend fun premiumScopedDecisionKeys(): List<String>

    @Query("DELETE FROM artwork_preview_links WHERE previewKey IN (:decisionKeys) OR canonicalKey IN (:decisionKeys)")
    protected abstract suspend fun deleteLinksReferencingDecisions(decisionKeys: List<String>): Int

    @Query("DELETE FROM artwork_decisions WHERE decisionKey IN (:decisionKeys)")
    protected abstract suspend fun deleteDecisionsByKeys(decisionKeys: List<String>): Int
}
