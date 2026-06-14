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
    open suspend fun deleteDecisionsBySettingsHash(settingsHash: String): Int {
        deleteLinksReferencingSettingsHash(settingsHash)
        return deleteDecisionRowsBySettingsHash(settingsHash)
    }

    @Transaction
    open suspend fun deleteDecisionsByCredentialHash(credentialHash: String): Int {
        deleteLinksReferencingCredentialHash(credentialHash)
        return deleteDecisionRowsByCredentialHash(credentialHash)
    }

    @Transaction
    open suspend fun deleteDecisionsByPolicyHashes(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ): Int {
        if (settingsHashes.isEmpty() && credentialHashes.isEmpty()) return 0
        return when {
            settingsHashes.isEmpty() -> {
                deleteLinksReferencingCredentialHashes(credentialHashes)
                deleteDecisionRowsByCredentialHashes(credentialHashes)
            }
            credentialHashes.isEmpty() -> {
                deleteLinksReferencingSettingsHashes(settingsHashes)
                deleteDecisionRowsBySettingsHashes(settingsHashes)
            }
            else -> {
                deleteLinksReferencingPolicyHashes(settingsHashes, credentialHashes)
                deleteDecisionRowsByPolicyHashes(settingsHashes, credentialHashes)
            }
        }
    }

    @Transaction
    open suspend fun deletePremiumScopedDecisions(): Int {
        deleteLinksReferencingPremiumScopedDecisions()
        return deletePremiumScopedDecisionRows()
    }

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

    @Query("DELETE FROM artwork_decisions WHERE settingsHash = :settingsHash")
    protected abstract suspend fun deleteDecisionRowsBySettingsHash(settingsHash: String): Int

    @Query("DELETE FROM artwork_decisions WHERE credentialHash = :credentialHash")
    protected abstract suspend fun deleteDecisionRowsByCredentialHash(credentialHash: String): Int

    @Query("DELETE FROM artwork_decisions WHERE settingsHash IN (:settingsHashes)")
    protected abstract suspend fun deleteDecisionRowsBySettingsHashes(settingsHashes: Set<String>): Int

    @Query("DELETE FROM artwork_decisions WHERE credentialHash IN (:credentialHashes)")
    protected abstract suspend fun deleteDecisionRowsByCredentialHashes(credentialHashes: Set<String>): Int

    @Query("DELETE FROM artwork_decisions WHERE settingsHash IN (:settingsHashes) OR credentialHash IN (:credentialHashes)")
    protected abstract suspend fun deleteDecisionRowsByPolicyHashes(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ): Int

    @Query("DELETE FROM artwork_decisions WHERE settingsHash IS NOT NULL OR credentialHash IS NOT NULL")
    protected abstract suspend fun deletePremiumScopedDecisionRows(): Int

    @Query(
        "DELETE FROM artwork_preview_links " +
            "WHERE previewKey IN (SELECT decisionKey FROM artwork_decisions WHERE settingsHash = :settingsHash) " +
            "OR canonicalKey IN (SELECT decisionKey FROM artwork_decisions WHERE settingsHash = :settingsHash)"
    )
    protected abstract suspend fun deleteLinksReferencingSettingsHash(settingsHash: String): Int

    @Query(
        "DELETE FROM artwork_preview_links " +
            "WHERE previewKey IN (SELECT decisionKey FROM artwork_decisions WHERE credentialHash = :credentialHash) " +
            "OR canonicalKey IN (SELECT decisionKey FROM artwork_decisions WHERE credentialHash = :credentialHash)"
    )
    protected abstract suspend fun deleteLinksReferencingCredentialHash(credentialHash: String): Int

    @Query(
        "DELETE FROM artwork_preview_links " +
            "WHERE previewKey IN (SELECT decisionKey FROM artwork_decisions WHERE settingsHash IN (:settingsHashes)) " +
            "OR canonicalKey IN (SELECT decisionKey FROM artwork_decisions WHERE settingsHash IN (:settingsHashes))"
    )
    protected abstract suspend fun deleteLinksReferencingSettingsHashes(settingsHashes: Set<String>): Int

    @Query(
        "DELETE FROM artwork_preview_links " +
            "WHERE previewKey IN (SELECT decisionKey FROM artwork_decisions WHERE credentialHash IN (:credentialHashes)) " +
            "OR canonicalKey IN (SELECT decisionKey FROM artwork_decisions WHERE credentialHash IN (:credentialHashes))"
    )
    protected abstract suspend fun deleteLinksReferencingCredentialHashes(credentialHashes: Set<String>): Int

    @Query(
        "DELETE FROM artwork_preview_links " +
            "WHERE previewKey IN (" +
            "SELECT decisionKey FROM artwork_decisions " +
            "WHERE settingsHash IN (:settingsHashes) OR credentialHash IN (:credentialHashes)" +
            ") OR canonicalKey IN (" +
            "SELECT decisionKey FROM artwork_decisions " +
            "WHERE settingsHash IN (:settingsHashes) OR credentialHash IN (:credentialHashes)" +
            ")"
    )
    protected abstract suspend fun deleteLinksReferencingPolicyHashes(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ): Int

    @Query(
        "DELETE FROM artwork_preview_links " +
            "WHERE previewKey IN (" +
            "SELECT decisionKey FROM artwork_decisions " +
            "WHERE settingsHash IS NOT NULL OR credentialHash IS NOT NULL" +
            ") OR canonicalKey IN (" +
            "SELECT decisionKey FROM artwork_decisions " +
            "WHERE settingsHash IS NOT NULL OR credentialHash IS NOT NULL" +
            ")"
    )
    protected abstract suspend fun deleteLinksReferencingPremiumScopedDecisions(): Int
}
