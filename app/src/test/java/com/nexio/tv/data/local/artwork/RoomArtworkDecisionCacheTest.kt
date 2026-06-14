package com.nexio.tv.data.local.artwork

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionCacheDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionJsonCodec
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDecisionLookupResult
import com.nexio.tv.core.artwork.ArtworkDecisionStoreLoadState
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.DurableArtworkDecisionCache
import com.nexio.tv.core.artwork.PersistedArtworkCandidate
import com.nexio.tv.core.artwork.PersistedProviderTemplate
import com.nexio.tv.core.artwork.RejectedArtworkCandidate
import com.nexio.tv.core.integration.IntegrationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomArtworkDecisionCacheTest {
    private val gson = Gson()
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `put lookup and preview link survive recreated store`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = missingLegacyFile("legacy-decisions.json")
        val decision = decision("room-decision")
        val previewKey = ArtworkDecisionKey("room-preview")

        store(db, legacyFile).apply {
            put(decision)
            linkPreviewToCanonical(previewKey, decision.decisionKey)
        }

        val restarted = store(db, legacyFile)

        assertEquals(ArtworkDecisionLookupResult.Found(decision), restarted.lookup(decision.decisionKey))
        assertEquals(decision, restarted.getCanonicalForPreview(previewKey))
        assertTrue(restarted.loadState() is ArtworkDecisionStoreLoadState.LoadedAuthoritative)
        assertEquals(1, restarted.snapshotDiagnostics().decisionCount)
        assertEquals(1, restarted.snapshotDiagnostics().linkCount)
    }

    @Test
    fun `imports legacy json decisions once`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = temp.newFile("legacy-decisions.json")
        val legacyDecision = decision("legacy-decision")
        val legacyPreview = ArtworkDecisionKey("legacy-preview")
        DurableArtworkDecisionCache(legacyFile, gson).apply {
            put(legacyDecision)
            linkPreviewToCanonical(legacyPreview, legacyDecision.decisionKey)
        }

        val first = store(db, legacyFile)

        assertEquals(ArtworkDecisionLookupResult.Found(legacyDecision), first.lookup(legacyDecision.decisionKey))
        assertEquals(legacyDecision, first.getCanonicalForPreview(legacyPreview))
        assertEquals("decisions-json-v1", db.migrationDao().get("decisions-json-v1")?.key)

        val notImported = decision("legacy-after-migration")
        DurableArtworkDecisionCache(legacyFile, gson).put(notImported)

        val restarted = store(db, legacyFile)

        assertNull(restarted.get(notImported.decisionKey))
        assertEquals(ArtworkDecisionLookupResult.Found(legacyDecision), restarted.lookup(legacyDecision.decisionKey))
    }

    @Test
    fun `premium invalidation removes matching decision and preview link`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = missingLegacyFile("premium-decisions.json")
        val premium = decision("premium-decision", settingsHash = "premium-settings", credentialHash = "credential")
        val primary = decision("primary-decision", settingsHash = null, credentialHash = null)
        val previewKey = ArtworkDecisionKey("premium-preview")
        val store = store(db, legacyFile)

        store.put(premium)
        store.put(primary)
        store.linkPreviewToCanonical(previewKey, premium.decisionKey)
        store.invalidateBySettingsHash("premium-settings")

        assertNull(store.get(premium.decisionKey))
        assertEquals(primary, store.get(primary.decisionKey))
        assertNull(store.getCanonicalForPreview(previewKey))
    }

    @Test
    fun `malformed Room payload is skipped without authoritative missing`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = missingLegacyFile("malformed-room.json")
        val mapper = ArtworkDecisionEntityMapper(ArtworkDecisionJsonCodec(gson))
        val valid = decision("valid-room")
        val malformed = decision("malformed-room")
        db.migrationDao().markComplete(
            ArtworkCacheMigrationEntity("decisions-json-v1", completedAtMs = 1000, importedCount = 0, skippedCount = 0)
        )
        db.decisionDao().upsertDecisions(
            listOf(
                mapper.toEntity(valid),
                mapper.toEntity(malformed).copy(payloadJson = "{bad json")
            )
        )

        val store = store(db, legacyFile)

        assertEquals(valid, store.get(valid.decisionKey))
        assertNull(store.get(malformed.decisionKey))
        assertTrue(store.loadState() is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative)
        assertTrue(store.lookup(ArtworkDecisionKey("missing")) is ArtworkDecisionLookupResult.CacheNotAuthoritative)
    }

    @Test
    fun `Room load failure delegates current and future operations to fallback`() = runTest {
        val db = inMemoryDatabase()
        val legacyFile = missingLegacyFile("closed-room.json")
        val fallback = InMemoryDecisionCache()
        val record = decision("fallback-decision")
        val store = store(db, legacyFile, fallback)
        db.close()

        store.put(record)

        assertEquals(record, fallback.get(record.decisionKey))
        assertEquals(record, store.get(record.decisionKey))
        assertEquals(ArtworkDecisionLookupResult.Found(record), store.lookup(record.decisionKey))
    }

    private fun store(
        db: ArtworkCacheDatabase,
        legacyFile: File,
        fallback: ArtworkDecisionCache = DurableArtworkDecisionCache(legacyFile, gson)
    ): RoomArtworkDecisionCache =
        RoomArtworkDecisionCache(
            dao = db.decisionDao(),
            migrationDao = db.migrationDao(),
            legacyFile = legacyFile,
            gson = gson,
            fallback = fallback
        )

    private fun inMemoryDatabase(): ArtworkCacheDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Room.inMemoryDatabaseBuilder(context, ArtworkCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun missingLegacyFile(name: String): File =
        temp.newFile(name).also { it.delete() }

    private fun decision(
        key: String,
        settingsHash: String? = "settings",
        credentialHash: String? = "credential"
    ): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey(key),
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                sourceRole = if (settingsHash == null && credentialHash == null) {
                    ArtworkSourceRole.PRIMARY
                } else {
                    ArtworkSourceRole.PREMIUM
                },
                sourceHash = "source-$key",
                redactedSourceForTrace = "https://example.test/<redacted>",
                providerTemplate = PersistedProviderTemplate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    imageType = ArtworkType.POSTER,
                    idType = "imdb",
                    mediaId = "tt0137523",
                    providerPathHash = "path-$key",
                    settingsHash = settingsHash,
                    credentialHash = credentialHash,
                    imageLanguage = "en",
                    policyVersion = 1,
                    pathParams = mapOf("size" to "poster")
                ),
                priority = 100
            ),
            rejectedCandidates = listOf(
                RejectedArtworkCandidate(
                    provider = ArtworkProviderId.Placeholder,
                    sourceRole = ArtworkSourceRole.PLACEHOLDER,
                    reason = "not-selected",
                    priority = 1
                )
            ),
            policyVersion = 1,
            imageLanguage = "en",
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            createdAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300
        )

    private class InMemoryDecisionCache : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
        private val decisions = linkedMapOf<ArtworkDecisionKey, ArtworkDecision>()
        private val previewLinks = linkedMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()

        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = decisions[key]

        override fun put(decision: ArtworkDecision) {
            decisions[decision.decisionKey] = decision
        }

        override fun remove(key: ArtworkDecisionKey) {
            decisions.remove(key)
            previewLinks.entries.removeIf { (previewKey, canonicalKey) ->
                previewKey == key || canonicalKey == key
            }
        }

        override fun linkPreviewToCanonical(previewKey: ArtworkDecisionKey, canonicalKey: ArtworkDecisionKey) {
            previewLinks[previewKey] = canonicalKey
        }

        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? =
            previewLinks[previewKey]?.let(decisions::get)

        override fun invalidateBySettingsHash(settingsHash: String) {
            invalidate { decision -> decision.settingsHash == settingsHash }
        }

        override fun invalidateByCredentialHash(credentialHash: String) {
            invalidate { decision -> decision.credentialHash == credentialHash }
        }

        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) {
            invalidate { decision -> decision.settingsHash in settingsHashes || decision.credentialHash in credentialHashes }
        }

        override fun invalidatePremiumArtworkPolicy() {
            invalidate { decision -> decision.settingsHash != null || decision.credentialHash != null }
        }

        override fun snapshotDiagnostics() =
            com.nexio.tv.core.artwork.ArtworkDecisionCacheSnapshotDiagnostics(
                loaded = true,
                decisionCount = decisions.size,
                linkCount = previewLinks.size,
                storeFilePresent = null,
                storeFileReadable = null,
                storeFileBytes = null,
                lastLoadSuccess = true,
                lastLoadReason = null,
                lastLoadErrorClass = null,
                droppedDecisionCount = 0
            )

        private fun invalidate(matches: (ArtworkDecision) -> Boolean) {
            decisions.values.filter(matches).map { it.decisionKey }.forEach(::remove)
        }
    }
}
