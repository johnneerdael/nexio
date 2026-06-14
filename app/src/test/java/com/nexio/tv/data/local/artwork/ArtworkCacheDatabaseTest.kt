package com.nexio.tv.data.local.artwork

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkCacheDatabaseTest {
    @Test
    fun `decision rows preview links and asset records round trip`() = runTest {
        val db = inMemoryDatabase()
        val decision = ArtworkDecisionEntity(
            decisionKey = "decision-a",
            ownerType = "canonical",
            ownerContentId = "imdb:tt0137523",
            ownerItemKey = null,
            ownerSourcePayloadHash = null,
            canonicalContentId = "imdb:tt0137523",
            imageType = "POSTER",
            selectedProviderKey = "RPDB",
            selectedSourceRole = "PREMIUM",
            settingsHash = "settings",
            credentialHash = "credential",
            policyVersion = 1,
            imageLanguage = "en",
            createdAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300,
            payloadJson = """{"decisionKey":"decision-a"}"""
        )
        val asset = ArtworkAssetRecordEntity(
            assetKey = "asset-a",
            decisionKey = "decision-a",
            providerKey = "RPDB",
            imageType = "POSTER",
            imageLanguage = "en",
            relativePath = "artwork-assets/RPDB/poster/asset-a.bin",
            mimeType = "image/jpeg",
            byteCount = 4,
            sourceHash = "source-a",
            policyVersion = 1,
            fetchedAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300
        )

        db.decisionDao().upsertDecision(decision)
        db.decisionDao().upsertPreviewLink(ArtworkPreviewLinkEntity("preview-a", "decision-a"))
        db.assetRecordDao().upsertAssetRecord(asset)
        db.migrationDao().markComplete(
            ArtworkCacheMigrationEntity(
                key = "decisions-json-v1",
                completedAtMs = 1234,
                importedCount = 1,
                skippedCount = 0
            )
        )

        assertEquals(decision, db.decisionDao().getDecision("decision-a"))
        assertEquals("decision-a", db.decisionDao().getCanonicalKeyForPreview("preview-a"))
        assertEquals(asset, db.assetRecordDao().getAssetRecord("asset-a"))
        assertEquals(asset, db.assetRecordDao().findLatestAssetForDecision("decision-a"))
        assertEquals("decisions-json-v1", db.migrationDao().get("decisions-json-v1")?.key)
    }

    @Test
    fun `latest asset lookup returns newest fetched row`() = runTest {
        val db = inMemoryDatabase()
        val older = asset("asset-old", "decision-a", fetchedAtMs = 100)
        val newer = asset("asset-new", "decision-a", fetchedAtMs = 200)

        db.assetRecordDao().upsertAssetRecord(older)
        db.assetRecordDao().upsertAssetRecord(newer)

        assertEquals(newer, db.assetRecordDao().findLatestAssetForDecision("decision-a"))
    }

    @Test
    fun `decision invalidation deletes matching links`() = runTest {
        val db = inMemoryDatabase()
        db.decisionDao().upsertDecision(decision("decision-a", settingsHash = "settings-a"))
        db.decisionDao().upsertDecision(decision("decision-b", settingsHash = null))
        db.decisionDao().upsertPreviewLink(ArtworkPreviewLinkEntity("preview-a", "decision-a"))

        db.decisionDao().deleteDecisionsBySettingsHash("settings-a")
        db.decisionDao().deleteLinksReferencingMissingDecisions()

        assertNull(db.decisionDao().getDecision("decision-a"))
        assertEquals("decision-b", db.decisionDao().getDecision("decision-b")?.decisionKey)
        assertNull(db.decisionDao().getCanonicalKeyForPreview("preview-a"))
    }

    @Test
    fun `missing decision cleanup preserves alias preview link when canonical decision exists`() = runTest {
        val db = inMemoryDatabase()
        db.decisionDao().upsertDecision(decision("canonical-a", settingsHash = null))
        db.decisionDao().upsertPreviewLink(ArtworkPreviewLinkEntity("preview-alias", "canonical-a"))

        db.decisionDao().deleteLinksReferencingMissingDecisions()

        assertEquals("canonical-a", db.decisionDao().getCanonicalKeyForPreview("preview-alias"))
    }

    @Test
    fun `delete decision and links removes links referencing preview decision`() = runTest {
        val db = inMemoryDatabase()
        db.decisionDao().upsertDecision(decision("preview-a", settingsHash = "settings-a"))
        db.decisionDao().upsertDecision(decision("canonical-a", settingsHash = null))
        db.decisionDao().upsertPreviewLink(ArtworkPreviewLinkEntity("preview-a", "canonical-a"))

        db.decisionDao().deleteDecisionAndLinks("preview-a")

        assertNull(db.decisionDao().getDecision("preview-a"))
        assertEquals("canonical-a", db.decisionDao().getDecision("canonical-a")?.decisionKey)
        assertNull(db.decisionDao().getCanonicalKeyForPreview("preview-a"))
    }

    private fun inMemoryDatabase(): ArtworkCacheDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.inMemoryDatabaseBuilder(context, ArtworkCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun decision(
        decisionKey: String,
        settingsHash: String?
    ): ArtworkDecisionEntity =
        ArtworkDecisionEntity(
            decisionKey = decisionKey,
            ownerType = "canonical",
            ownerContentId = "imdb:tt0137523",
            ownerItemKey = null,
            ownerSourcePayloadHash = null,
            canonicalContentId = "imdb:tt0137523",
            imageType = "POSTER",
            selectedProviderKey = "RPDB",
            selectedSourceRole = "PREMIUM",
            settingsHash = settingsHash,
            credentialHash = null,
            policyVersion = 1,
            imageLanguage = "en",
            createdAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300,
            payloadJson = """{"decisionKey":"$decisionKey"}"""
        )

    private fun asset(
        assetKey: String,
        decisionKey: String,
        fetchedAtMs: Long
    ): ArtworkAssetRecordEntity =
        ArtworkAssetRecordEntity(
            assetKey = assetKey,
            decisionKey = decisionKey,
            providerKey = "RPDB",
            imageType = "POSTER",
            imageLanguage = "en",
            relativePath = "artwork-assets/RPDB/poster/$assetKey.bin",
            mimeType = "image/jpeg",
            byteCount = 4,
            sourceHash = "source-$assetKey",
            policyVersion = 1,
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = fetchedAtMs + 100,
            staleUntilMs = fetchedAtMs + 200
        )
}
