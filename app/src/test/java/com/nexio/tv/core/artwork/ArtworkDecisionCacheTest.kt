package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Modifier

class ArtworkDecisionCacheTest {
    private val cache = InMemoryArtworkDecisionCache()

    @Test
    fun `in memory lookup returns found or missing authoritative`() {
        val key = ArtworkDecisionKey("lookup-in-memory")
        val decision = decision(key, ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"))
        val mismatchedContext = authorityContext()

        cache.put(decision)

        assertEquals(ArtworkDecisionLookupResult.Found(decision), cache.lookup(key))
        val missingKey = ArtworkDecisionKey("lookup-in-memory-missing")
        assertMissingAuthoritative(cache.lookup(missingKey), missingKey)
        assertCacheNotAuthoritative(
            result = cache.lookup(missingKey, mismatchedContext),
            key = missingKey,
            reason = "authority_context_mismatch",
            requiredContext = mismatchedContext
        )
    }

    @Test
    fun `default lookup returns lookup failed when get throws`() {
        val throwingCache = object : ArtworkDecisionCache {
            override fun get(key: ArtworkDecisionKey): ArtworkDecision? {
                error("lookup exploded")
            }

            override fun put(decision: ArtworkDecision) = Unit
            override fun remove(key: ArtworkDecisionKey) = Unit
            override fun linkPreviewToCanonical(
                previewKey: ArtworkDecisionKey,
                canonicalKey: ArtworkDecisionKey
            ) = Unit
            override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null
            override fun invalidateBySettingsHash(settingsHash: String) = Unit
            override fun invalidateByCredentialHash(credentialHash: String) = Unit
            override fun invalidateArtworkPolicy(
                settingsHashes: Set<String>,
                credentialHashes: Set<String>
            ) = Unit
            override fun invalidatePremiumArtworkPolicy() = Unit
        }

        val result = throwingCache.lookup(ArtworkDecisionKey("throwing-lookup"))

        assertTrue(result is ArtworkDecisionLookupResult.LookupFailed)
        result as ArtworkDecisionLookupResult.LookupFailed
        assertEquals(ArtworkDecisionKey("throwing-lookup"), result.decisionKey)
        assertEquals("IllegalStateException", result.errorClass)
        assertNotNull(result.messageHash)
    }

    @Test
    fun `loaded authoritative requires no dropped or quarantined decisions`() {
        val context = authorityContext()

        assertTrue(
            ArtworkDecisionStoreLoadState.LoadedAuthoritative(
                decisionCount = 0,
                authorityContext = context,
                droppedDecisionCount = 0,
                quarantinedDecisionCount = 0
            ).isAuthoritativeForMissing(context)
        )
        assertFalse(
            ArtworkDecisionStoreLoadState.LoadedAuthoritative(
                decisionCount = 0,
                authorityContext = context,
                droppedDecisionCount = 1,
                quarantinedDecisionCount = 0
            ).isAuthoritativeForMissing(context)
        )
        assertFalse(
            ArtworkDecisionStoreLoadState.LoadedAuthoritative(
                decisionCount = 0,
                authorityContext = context,
                droppedDecisionCount = 0,
                quarantinedDecisionCount = 1
            ).isAuthoritativeForMissing(context)
        )
    }

    @Test
    fun `canonical decision supersedes preview decision without deleting preview fallback`() {
        val previewKey = ArtworkDecisionKey("preview-decision")
        val canonicalKey = ArtworkDecisionKey("canonical-decision")
        val preview = decision(previewKey, ArtworkOwnerKey.PreviewItem("row1", "payloadhash"))
        val canonical = decision(canonicalKey, ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"))

        cache.put(preview)
        cache.put(canonical)
        cache.linkPreviewToCanonical(previewKey, canonicalKey)

        assertEquals(canonical, cache.getCanonicalForPreview(previewKey))
        assertEquals(preview, cache.get(previewKey))
    }

    @Test
    fun `premium policy invalidation removes matching decisions only`() {
        val premium = decision(
            ArtworkDecisionKey("premium"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            settingsHash = "a"
        )
        val native = decision(
            ArtworkDecisionKey("native"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            settingsHash = null
        )

        cache.put(premium)
        cache.put(native)
        cache.invalidateBySettingsHash("a")

        assertNull(cache.get(premium.decisionKey))
        assertEquals(native, cache.get(native.decisionKey))
    }

    @Test
    fun `provider switch invalidates artwork policy hashes only`() {
        val rpdb = decision(
            ArtworkDecisionKey("rpdb"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            settingsHash = "rpdb-settings"
        )
        val tmdb = decision(
            ArtworkDecisionKey("tmdb"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            settingsHash = null
        )

        cache.put(rpdb)
        cache.put(tmdb)
        cache.invalidateArtworkPolicy(
            settingsHashes = setOf("rpdb-settings"),
            credentialHashes = emptySet()
        )

        assertNull(cache.get(rpdb.decisionKey))
        assertEquals(tmdb, cache.get(tmdb.decisionKey))
    }

    @Test
    fun `credential invalidation removes matching decisions only`() {
        val keyed = decision(
            ArtworkDecisionKey("keyed"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            credentialHash = "cred"
        )
        val unkeyed = decision(
            ArtworkDecisionKey("unkeyed"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            credentialHash = null
        )

        cache.put(keyed)
        cache.put(unkeyed)
        cache.invalidateByCredentialHash("cred")

        assertNull(cache.get(keyed.decisionKey))
        assertEquals(unkeyed, cache.get(unkeyed.decisionKey))
    }

    @Test
    fun `artwork policy invalidation includes credential hashes`() {
        val keyed = decision(
            ArtworkDecisionKey("policy-keyed"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            credentialHash = "credential-policy"
        )
        val unmatched = decision(
            ArtworkDecisionKey("policy-unmatched"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            credentialHash = "other-credential"
        )

        cache.put(keyed)
        cache.put(unmatched)
        cache.invalidateArtworkPolicy(
            settingsHashes = emptySet(),
            credentialHashes = setOf("credential-policy")
        )

        assertNull(cache.get(keyed.decisionKey))
        assertEquals(unmatched, cache.get(unmatched.decisionKey))
    }

    @Test
    fun `premium artwork policy invalidation removes settings or credential scoped decisions only`() {
        val settingsScoped = decision(
            ArtworkDecisionKey("settings-scoped"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            settingsHash = "premium-settings"
        )
        val credentialScoped = decision(
            ArtworkDecisionKey("credential-scoped"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            credentialHash = "premium-credential"
        )
        val primary = decision(
            ArtworkDecisionKey("primary"),
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523")
        )

        cache.put(settingsScoped)
        cache.put(credentialScoped)
        cache.put(primary)
        cache.invalidatePremiumArtworkPolicy()

        assertNull(cache.get(settingsScoped.decisionKey))
        assertNull(cache.get(credentialScoped.decisionKey))
        assertEquals(primary, cache.get(primary.decisionKey))
    }

    @Test
    fun `premium artwork policy invalidation removes links to invalidated premium canonical decisions`() {
        val previewKey = ArtworkDecisionKey("premium-preview-link")
        val canonicalKey = ArtworkDecisionKey("premium-canonical-link")
        val preview = decision(previewKey, ArtworkOwnerKey.PreviewItem("row1", "payloadhash"))
        val canonical = decision(
            canonicalKey,
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            settingsHash = "premium-settings"
        )

        cache.put(preview)
        cache.put(canonical)
        cache.linkPreviewToCanonical(previewKey, canonicalKey)
        cache.invalidatePremiumArtworkPolicy()

        assertNull(cache.getCanonicalForPreview(previewKey))
        assertEquals(preview, cache.get(previewKey))
    }

    @Test
    fun `invalidating canonical decision removes preview link but keeps preview fallback`() {
        val previewKey = ArtworkDecisionKey("preview-link-fallback")
        val canonicalKey = ArtworkDecisionKey("canonical-link-invalidate")
        val preview = decision(previewKey, ArtworkOwnerKey.PreviewItem("row1", "payloadhash"))
        val canonical = decision(
            canonicalKey,
            ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            settingsHash = "canonical-settings"
        )

        cache.put(preview)
        cache.put(canonical)
        cache.linkPreviewToCanonical(previewKey, canonicalKey)
        cache.invalidateBySettingsHash("canonical-settings")

        assertNull(cache.getCanonicalForPreview(previewKey))
        assertEquals(preview, cache.get(previewKey))
    }

    @Test
    fun `invalidating preview decision removes outgoing preview link`() {
        val previewKey = ArtworkDecisionKey("preview-link-invalidate")
        val canonicalKey = ArtworkDecisionKey("canonical-link-survives")
        val preview = decision(
            previewKey,
            ArtworkOwnerKey.PreviewItem("row1", "payloadhash"),
            settingsHash = "preview-settings"
        )
        val canonical = decision(canonicalKey, ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"))

        cache.put(preview)
        cache.put(canonical)
        cache.linkPreviewToCanonical(previewKey, canonicalKey)
        cache.invalidateBySettingsHash("preview-settings")

        assertNull(cache.get(previewKey))
        assertNull(cache.getCanonicalForPreview(previewKey))
        assertEquals(canonical, cache.get(canonicalKey))
    }

    @Test
    fun `durable clean load is authoritative and missing lookup is authoritative`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()

        first.put(decision)
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())

        assertEquals(ArtworkDecisionLookupResult.Found(decision), restarted.lookup(decision.decisionKey))
        val missingKey = ArtworkDecisionKey("durable-clean-missing")
        assertMissingAuthoritative(restarted.lookup(missingKey), missingKey)
        assertEquals(ArtworkDecisionStoreLoadState.LoadedAuthoritative::class, restarted.loadState()::class)
        assertTrue(restarted.loadState().isAuthoritativeForMissing())
        assertTrue(restarted.snapshotDiagnostics().authoritative)
    }

    @Test
    fun `durable missing file load is authoritative and missing lookup is authoritative`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.root.resolve("missing-artwork-decisions.json")
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())

        val missingKey = ArtworkDecisionKey("missing-file-missing")
        assertMissingAuthoritative(restarted.lookup(missingKey), missingKey)

        val diagnostics = restarted.snapshotDiagnostics()
        assertEquals("LoadedAuthoritative", diagnostics.loadStateName)
        assertTrue(diagnostics.authoritative)
        assertEquals(false, diagnostics.storeFilePresent)
        assertEquals(0, diagnostics.droppedDecisionCount)
        assertEquals(0, diagnostics.quarantinedDecisionCount)
    }

    @Test
    fun `durable blank file load is authoritative and missing lookup is authoritative`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        file.writeText("  \n  ")
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())

        val missingKey = ArtworkDecisionKey("blank-file-missing")
        assertMissingAuthoritative(restarted.lookup(missingKey), missingKey)

        val diagnostics = restarted.snapshotDiagnostics()
        assertEquals("LoadedAuthoritative", diagnostics.loadStateName)
        assertTrue(diagnostics.authoritative)
        assertEquals(true, diagnostics.storeFilePresent)
        assertEquals(0, diagnostics.droppedDecisionCount)
        assertEquals(0, diagnostics.quarantinedDecisionCount)
    }

    @Test
    fun `durable malformed decision is quarantined while valid decisions remain usable`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()

        first.put(decision)
        file.writeText(file.readText().replace("],\"previewLinks\"", ",{}],\"previewLinks\""))

        val traceSink = RecordingTraceSink()
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)

        assertEquals(ArtworkDecisionLookupResult.Found(decision), restarted.lookup(decision.decisionKey))
        val missingKey = ArtworkDecisionKey("durable-partial-missing")
        assertCacheNotAuthoritative(
            result = restarted.lookup(missingKey),
            key = missingKey,
            reason = "partial_load"
        )
        val diagnostics = restarted.snapshotDiagnostics()
        assertEquals("LoadedPartialNonAuthoritative", diagnostics.loadStateName)
        assertFalse(diagnostics.authoritative)
        assertEquals(1, diagnostics.quarantinedDecisionCount)
        assertNotNull(diagnostics.firstQuarantinedDecisionKeyHash)

        val payload = traceSink.events
            .single { event -> event.eventType == "artwork.decision_store_load" }
            .payload as Map<*, *>
        assertEquals(true, payload["success"])
        assertEquals(false, payload["authoritative"])
        assertEquals("LoadedPartialNonAuthoritative", payload["loadState"])
        assertEquals(1, payload["decisionCount"])
        assertEquals(1, payload["quarantinedDecisionCount"])
        assertNotNull(payload["firstQuarantinedDecisionKeyHash"])
    }

    @Test
    fun `durable malformed dto parse value is quarantined while valid decisions remain usable`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()

        first.put(decision)
        val store = JsonParser.parseString(file.readText()).asJsonObject
        val validDecision = store.getAsJsonArray("decisions").first().asJsonObject
        val malformedDecision = validDecision.deepCopy().asJsonObject
        malformedDecision.add("policyVersion", JsonObject())
        store.add("decisions", JsonArray().apply {
            add(validDecision)
            add(malformedDecision)
        })
        file.writeText(Gson().toJson(store))

        val traceSink = RecordingTraceSink()
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)

        assertEquals(ArtworkDecisionLookupResult.Found(decision), restarted.lookup(decision.decisionKey))
        val missingKey = ArtworkDecisionKey("durable-dto-parse-missing")
        assertCacheNotAuthoritative(
            result = restarted.lookup(missingKey),
            key = missingKey,
            reason = "partial_load"
        )

        val diagnostics = restarted.snapshotDiagnostics()
        assertEquals("LoadedPartialNonAuthoritative", diagnostics.loadStateName)
        assertFalse(diagnostics.authoritative)
        assertEquals(1, diagnostics.quarantinedDecisionCount)
        assertNotNull(diagnostics.firstQuarantinedDecisionKeyHash)

        val payload = traceSink.events
            .single { event -> event.eventType == "artwork.decision_store_load" }
            .payload as Map<*, *>
        assertEquals(true, payload["success"])
        assertEquals("LoadedPartialNonAuthoritative", payload["loadState"])
        assertEquals(1, payload["decisionCount"])
        assertEquals(1, payload["quarantinedDecisionCount"])
    }

    @Test
    fun `durable top level parse failure is failed non authoritative`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        file.writeText("{not-json")

        val traceSink = RecordingTraceSink()
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)

        val missingKey = ArtworkDecisionKey("parse-failure-missing")
        assertCacheNotAuthoritative(
            result = restarted.lookup(missingKey),
            key = missingKey,
            reason = "load_failed",
            errorClass = "JsonSyntaxException"
        )
        assertEquals(ArtworkDecisionStoreLoadState.FailedNonAuthoritative::class, restarted.loadState()::class)
        val diagnostics = restarted.snapshotDiagnostics()
        assertEquals("FailedNonAuthoritative", diagnostics.loadStateName)
        assertFalse(diagnostics.authoritative)
        assertNotNull(diagnostics.errorMessageHash)
        assertNotNull(diagnostics.errorTopFrame)

        val payload = traceSink.events
            .single { event -> event.eventType == "artwork.decision_store_load" }
            .payload as Map<*, *>
        assertEquals(false, payload["success"])
        assertEquals(false, payload["authoritative"])
        assertEquals("FailedNonAuthoritative", payload["loadState"])
        assertNotNull(payload["errorMessageHash"])
        assertNotNull(payload["errorTopFrame"])
    }

    @Test
    fun `durable schema mismatch is failed non authoritative`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        DurableArtworkDecisionCache(file = file, gson = Gson()).put(durableRpdbDecision())
        val store = JsonParser.parseString(file.readText()).asJsonObject
        store.addProperty("schemaVersion", 0)
        file.writeText(Gson().toJson(store))

        val traceSink = RecordingTraceSink()
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)

        val missingKey = ArtworkDecisionKey("schema-mismatch-missing")
        assertCacheNotAuthoritative(
            result = restarted.lookup(missingKey),
            key = missingKey,
            reason = "load_failed",
            errorClass = "SchemaVersionMismatch"
        )

        val diagnostics = restarted.snapshotDiagnostics()
        assertEquals("FailedNonAuthoritative", diagnostics.loadStateName)
        assertFalse(diagnostics.authoritative)
        assertEquals(false, diagnostics.lastLoadSuccess)
        assertEquals("schema_version_mismatch", diagnostics.lastLoadReason)
        assertEquals(0, diagnostics.storedSchemaVersion)
        assertEquals(1, diagnostics.droppedDecisionCount)
        assertEquals(1, diagnostics.quarantinedDecisionCount)

        val payload = traceSink.events
            .single { event -> event.eventType == "artwork.decision_store_load" }
            .payload as Map<*, *>
        assertEquals(false, payload["success"])
        assertEquals(false, payload["authoritative"])
        assertEquals("FailedNonAuthoritative", payload["loadState"])
        assertEquals("schema_version_mismatch", payload["reason"])
        assertEquals(0, payload["storedSchemaVersion"])
        assertEquals(1, payload["droppedDecisionCount"])
        assertEquals(1, payload["quarantinedDecisionCount"])
    }

    @Test
    fun `durable missing authoritative requires matching authority context`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        DurableArtworkDecisionCache(file = file, gson = Gson()).put(durableRpdbDecision())
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())

        restarted.get(ArtworkDecisionKey("warm-load"))
        val context = restarted.snapshotDiagnostics().authorityContext
        assertNotNull(context)
        val mismatched = context!!.copy(imageLanguage = "nl")

        assertNotEquals(context, mismatched)
        val matchingKey = ArtworkDecisionKey("matching-context-missing")
        assertMissingAuthoritative(restarted.lookup(matchingKey, context), matchingKey)
        val mismatchedKey = ArtworkDecisionKey("mismatched-context-missing")
        assertCacheNotAuthoritative(
            result = restarted.lookup(mismatchedKey, mismatched),
            key = mismatchedKey,
            reason = "authority_context_mismatch",
            requiredContext = mismatched
        )
    }

    @Test
    fun `durable cache survives process restart without raw secrets`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()

        first.put(decision)

        val raw = file.readText()
        assertTrue(raw.contains(decision.decisionKey.value))
        assertFalse(raw.contains("rpdb-key"))
        assertFalse(raw.contains("https://api.ratingposterdb.com"))
        assertFalse(raw.contains("https://api.top-posters.com"))

        val second = DurableArtworkDecisionCache(file = file, gson = Gson())
        val restored = second.get(decision.decisionKey)

        assertEquals(decision.decisionKey, restored?.decisionKey)
        assertEquals("RPDB", restored?.selectedCandidate?.provider?.key)
        assertEquals("imdb", restored?.selectedCandidate?.providerTemplate?.idType)
        assertEquals("tt15940132", restored?.selectedCandidate?.providerTemplate?.mediaId)
        assertEquals(decision.credentialHash, restored?.credentialHash)
    }

    @Test
    fun `durable cache restores valid decisions when one persisted decision is malformed`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()

        first.put(decision)
        file.writeText(file.readText().replace("],\"previewLinks\"", ",{}],\"previewLinks\""))

        val traceSink = RecordingTraceSink()
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)
        val restored = restarted.get(decision.decisionKey)

        assertEquals(decision.decisionKey, restored?.decisionKey)
        val load = traceSink.events.single { event -> event.eventType == "artwork.decision_store_load" }
        val payload = load.payload as Map<*, *>
        assertEquals(true, payload["success"])
        assertEquals(1, payload["decisionCount"])
        assertEquals(1, payload["droppedDecisionCount"])
    }

    @Test
    fun `durable cache exposes load diagnostics after lookup`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()
        first.put(decision)
        val persistedBytes = file.length()

        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())
        assertEquals(decision.decisionKey, restarted.get(decision.decisionKey)?.decisionKey)

        val diagnostics = restarted.snapshotDiagnostics()
        assertEquals(true, diagnostics.loaded)
        assertEquals(true, diagnostics.lastLoadSuccess)
        assertEquals(null, diagnostics.lastLoadReason)
        assertEquals(null, diagnostics.lastLoadErrorClass)
        assertEquals(true, diagnostics.storeFilePresent)
        assertEquals(true, diagnostics.storeFileReadable)
        assertEquals(persistedBytes, diagnostics.storeFileBytes)
        assertEquals(1, diagnostics.decisionCount)
        assertEquals(0, diagnostics.linkCount)
        assertEquals(0, diagnostics.droppedDecisionCount)
    }

    @Test
    fun `durable cache load trace includes file stats`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()
        first.put(decision)
        val traceSink = RecordingTraceSink()
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)

        restarted.get(decision.decisionKey)

        val payload = traceSink.events
            .single { event -> event.eventType == "artwork.decision_store_load" }
            .payload as Map<*, *>
        assertEquals(true, payload["success"])
        assertEquals(true, payload["filePresent"])
        assertEquals(true, payload["fileReadable"])
        assertEquals(file.length(), payload["fileBytes"])
        assertEquals(1, payload["decisionCount"])
    }

    @Test
    fun `durable cache emits put and write trace events`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val traceSink = RecordingTraceSink()
        val cache = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)
        val decision = durableRpdbDecision()

        cache.put(decision)

        val putPayload = traceSink.events
            .single { event -> event.eventType == "artwork.decision_put" }
            .payload as Map<*, *>
        assertEquals(decision.decisionKey.value, putPayload["decisionKey"])
        assertEquals("RPDB", putPayload["provider"])
        assertEquals("POSTER", putPayload["imageType"])
        assertEquals(1, putPayload["rejectedCount"])

        val writePayload = traceSink.events
            .last { event -> event.eventType == "artwork.decision_store_write" }
            .payload as Map<*, *>
        assertEquals(true, writePayload["success"])
        assertEquals(1, writePayload["decisionCount"])
        assertEquals(0, writePayload["linkCount"])
    }

    @Test
    fun `durable cache skips timestamp only duplicate decision write`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val traceSink = RecordingTraceSink()
        val cache = DurableArtworkDecisionCache(file = file, gson = Gson(), traceSink = traceSink)
        val decision = durableRpdbDecision()
        val refreshedTimestampDecision = decision.copy(
            createdAtMs = decision.createdAtMs + 1_000L,
            expiresAtMs = decision.expiresAtMs + 1_000L,
            staleUntilMs = decision.staleUntilMs?.plus(1_000L)
        )

        cache.put(decision)
        val writesAfterFirstPut = traceSink.events.count { event ->
            event.eventType == "artwork.decision_store_write"
        }

        cache.put(refreshedTimestampDecision)

        assertEquals(
            writesAfterFirstPut,
            traceSink.events.count { event -> event.eventType == "artwork.decision_store_write" }
        )
        assertEquals(decision, cache.get(decision.decisionKey))
    }

    @Test
    fun `durable cache batches thumbnail decision writes while keeping read your write`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val traceSink = RecordingTraceSink()
        val cache = DurableArtworkDecisionCache(
            file = file,
            gson = Gson(),
            traceSink = traceSink,
            thumbnailWriteDebounceMs = 60_000L
        )
        val decisions = (1..5).map(::durableThumbnailDecision)

        decisions.forEach(cache::put)

        assertEquals(
            0,
            traceSink.events.count { event -> event.eventType == "artwork.decision_store_write" }
        )
        decisions.forEach { decision ->
            assertEquals(decision, cache.get(decision.decisionKey))
        }

        cache.flushPendingWritesForTest()

        assertEquals(
            1,
            traceSink.events.count { event -> event.eventType == "artwork.decision_store_write" }
        )
        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())
        decisions.forEach { decision ->
            assertEquals(decision, restarted.get(decision.decisionKey))
        }
    }

    @Test
    fun `durable cache remove deletes persisted decision`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val cache = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()

        cache.put(decision)
        cache.remove(decision.decisionKey)

        val restarted = DurableArtworkDecisionCache(file = file, gson = Gson())
        assertNull(restarted.get(decision.decisionKey))
    }

    @Test
    fun `durable cache restores rejected fallback candidate source data`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val first = DurableArtworkDecisionCache(file = file, gson = Gson())
        val fallbackTemplate = PersistedProviderTemplate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.POSTER,
            idType = "tmdb",
            mediaId = "550",
            providerPathHash = "fallbackpathhash",
            settingsHash = null,
            credentialHash = null,
            imageLanguage = "en",
            policyVersion = 1
        )
        val decision = durableRpdbDecision().copy(
            rejectedCandidates = listOf(
                RejectedArtworkCandidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    sourceRole = ArtworkSourceRole.PRIMARY,
                    reason = "available_fallback",
                    sourceHash = "fallbacksourcehash",
                    redactedSourceForTrace = "https://image.tmdb.org/t/p/w500/<redacted>",
                    providerTemplate = fallbackTemplate,
                    priority = 10
                )
            )
        )

        first.put(decision)

        val restored = DurableArtworkDecisionCache(file = file, gson = Gson())
            .get(decision.decisionKey)
            ?.rejectedCandidates
            ?.single()

        assertEquals("fallbacksourcehash", restored?.sourceHash)
        assertEquals("https://image.tmdb.org/t/p/w500/<redacted>", restored?.redactedSourceForTrace)
        assertEquals(fallbackTemplate, restored?.providerTemplate)
        assertEquals(10, restored?.priority)
    }

    @Test
    fun `durable cache invalidates premium decisions by credential hash`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val cache = DurableArtworkDecisionCache(file = file, gson = Gson())
        val decision = durableRpdbDecision()

        cache.put(decision)
        cache.invalidateByCredentialHash("credentialhash")

        assertNull(cache.get(decision.decisionKey))
    }

    @Test
    fun `durable cache keeps in-memory decision when persistence fails`() {
        val temp = TemporaryFolder().also { it.create() }
        val unwritableTarget = temp.newFolder("artwork-decisions.json")
        val traceSink = RecordingTraceSink()
        val cache = DurableArtworkDecisionCache(
            file = unwritableTarget,
            gson = Gson(),
            traceSink = traceSink
        )
        val decision = durableRpdbDecision()

        try {
            cache.put(decision)
        } catch (error: Exception) {
            fail("put should not throw when durable persistence fails: $error")
        }

        assertEquals(decision, cache.get(decision.decisionKey))
        val writePayload = traceSink.events
            .last { event -> event.eventType == "artwork.decision_store_write" }
            .payload as Map<*, *>
        assertEquals(false, writePayload["success"])
        assertNotNull(writePayload["errorClass"])
    }

    @Test
    fun `durable cache JSON uses stable serialized field names`() {
        val temp = TemporaryFolder().also { it.create() }
        val file = temp.newFile("artwork-decisions.json")
        val cache = DurableArtworkDecisionCache(file = file, gson = Gson())

        cache.put(durableRpdbDecision())

        val raw = file.readText()
        assertTrue(raw.contains("\"schemaVersion\""))
        assertTrue(raw.contains("\"decisions\""))
        assertTrue(raw.contains("\"previewLinks\""))
        assertTrue(raw.contains("\"decisionKey\""))
        assertTrue(raw.contains("\"selectedCandidate\""))
        assertTrue(raw.contains("\"providerTemplate\""))
        assertTrue(raw.contains("\"credentialHash\""))
    }

    @Test
    fun `durable cache persisted DTO fields declare stable serialized names`() {
        assertSerializedNames(
            className = "StoreDto",
            expected = mapOf(
                "schemaVersion" to "schemaVersion",
                "decisions" to "decisions",
                "previewLinks" to "previewLinks"
            )
        )
        assertSerializedNames(
            className = "PreviewLinkDto",
            expected = mapOf(
                "previewKey" to "previewKey",
                "canonicalKey" to "canonicalKey"
            )
        )
        assertSerializedNames(
            className = "DecisionDto",
            expected = mapOf(
                "decisionKey" to "decisionKey",
                "owner" to "owner",
                "canonicalContentId" to "canonicalContentId",
                "imageType" to "imageType",
                "selectedCandidate" to "selectedCandidate",
                "rejectedCandidates" to "rejectedCandidates",
                "policyVersion" to "policyVersion",
                "imageLanguage" to "imageLanguage",
                "settingsHash" to "settingsHash",
                "credentialHash" to "credentialHash",
                "createdAtMs" to "createdAtMs",
                "expiresAtMs" to "expiresAtMs",
                "staleUntilMs" to "staleUntilMs"
            )
        )
        assertSerializedNames(
            className = "OwnerDto",
            expected = mapOf(
                "type" to "type",
                "contentId" to "contentId",
                "itemKey" to "itemKey",
                "sourcePayloadHash" to "sourcePayloadHash"
            )
        )
        assertSerializedNames(
            className = "CandidateDto",
            expected = mapOf(
                "provider" to "provider",
                "sourceRole" to "sourceRole",
                "sourceHash" to "sourceHash",
                "redactedSourceForTrace" to "redactedSourceForTrace",
                "providerTemplate" to "providerTemplate",
                "priority" to "priority"
            )
        )
        assertSerializedNames(
            className = "RejectedDto",
            expected = mapOf(
                "provider" to "provider",
                "sourceRole" to "sourceRole",
                "reason" to "reason",
                "sourceHash" to "sourceHash",
                "redactedSourceForTrace" to "redactedSourceForTrace",
                "providerTemplate" to "providerTemplate",
                "priority" to "priority"
            )
        )
        assertSerializedNames(
            className = "TemplateDto",
            expected = mapOf(
                "provider" to "provider",
                "imageType" to "imageType",
                "idType" to "idType",
                "mediaId" to "mediaId",
                "providerPathHash" to "providerPathHash",
                "settingsHash" to "settingsHash",
                "credentialHash" to "credentialHash",
                "imageLanguage" to "imageLanguage",
                "policyVersion" to "policyVersion",
                "pathParams" to "pathParams"
            )
        )
        assertSerializedNames(
            className = "ProviderDto",
            expected = mapOf(
                "type" to "type",
                "integrationProvider" to "integrationProvider"
            )
        )
    }

    private fun decision(
        key: ArtworkDecisionKey,
        ownerKey: ArtworkOwnerKey,
        settingsHash: String? = null,
        credentialHash: String? = null
    ): ArtworkDecision =
        ArtworkDecision(
            decisionKey = key,
            ownerKey = ownerKey,
            canonicalContentId = (ownerKey as? ArtworkOwnerKey.CanonicalContent)?.contentId,
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.Placeholder,
                sourceRole = ArtworkSourceRole.PLACEHOLDER,
                sourceHash = null,
                redactedSourceForTrace = null,
                providerTemplate = null,
                priority = 90
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            createdAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300
        )

    private fun durableRpdbDecision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey(
                "artwork-decision:poster:canonical:imdb:tt15940132:provider:RPDB:" +
                    "premium:true:settings:settingshash:credential:credentialhash:imageLang:en:policy:1"
            ),
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt15940132"),
            canonicalContentId = "imdb:tt15940132",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "sourcehash",
                redactedSourceForTrace = null,
                providerTemplate = PersistedProviderTemplate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    imageType = ArtworkType.POSTER,
                    idType = "imdb",
                    mediaId = "tt15940132",
                    providerPathHash = "pathhash",
                    settingsHash = "settingshash",
                    credentialHash = "credentialhash",
                    imageLanguage = "en",
                    policyVersion = 1
                ),
                priority = 100
            ),
            rejectedCandidates = listOf(
                RejectedArtworkCandidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    sourceRole = ArtworkSourceRole.PRIMARY,
                    reason = "premium_selected"
                )
            ),
            policyVersion = 1,
            imageLanguage = "en",
            settingsHash = "settingshash",
            credentialHash = "credentialhash",
            createdAtMs = 1_000L,
            expiresAtMs = 2_000L,
            staleUntilMs = 3_000L
        )

    private fun durableThumbnailDecision(index: Int): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey(
                "artwork-decision:thumbnail:canonical:tt0239195:S1E$index:provider:TVDB:" +
                    "premium:false:settings:settingshash:credential:none:imageLang:en:policy:1"
            ),
            ownerKey = ArtworkOwnerKey.CanonicalContent("tt0239195:S1E$index"),
            canonicalContentId = "tt0239195:S1E$index",
            imageType = ArtworkType.THUMBNAIL,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
                sourceRole = ArtworkSourceRole.PRIMARY,
                sourceHash = "thumbnail-sourcehash-$index",
                redactedSourceForTrace = "https://artwork.example.test/thumb-$index.jpg",
                providerTemplate = PersistedProviderTemplate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
                    imageType = ArtworkType.THUMBNAIL,
                    idType = "tvdb",
                    mediaId = "episode-$index",
                    providerPathHash = "thumbnail-pathhash-$index",
                    settingsHash = "settingshash",
                    credentialHash = null,
                    imageLanguage = "en",
                    policyVersion = 1
                ),
                priority = 10
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            imageLanguage = "en",
            settingsHash = "settingshash",
            credentialHash = null,
            createdAtMs = 1_000L + index,
            expiresAtMs = 2_000L + index,
            staleUntilMs = 3_000L + index
        )

    private fun assertSerializedNames(
        className: String,
        expected: Map<String, String>
    ) {
        val dtoClass = Class.forName(
            "${DurableArtworkDecisionCache::class.qualifiedName}$$className"
        )
        val persistedFields = dtoClass.declaredFields
            .filter { field -> !field.isSynthetic && !Modifier.isStatic(field.modifiers) }
            .associateBy { field -> field.name }

        assertEquals(expected.keys, persistedFields.keys)
        expected.forEach { (fieldName, serializedName) ->
            val annotation = persistedFields
                .getValue(fieldName)
                .getAnnotation(SerializedName::class.java)
            assertEquals(serializedName, annotation?.value)
        }
    }

    private fun authorityContext(): ArtworkDecisionAuthorityContext =
        ArtworkDecisionAuthorityContext(
            storeIdHash = "store",
            schemaVersion = 1,
            providerPolicyHash = "policy",
            settingsHash = "settings",
            credentialHash = "credential",
            imageLanguage = "en"
        )

    private fun assertMissingAuthoritative(
        result: ArtworkDecisionLookupResult,
        key: ArtworkDecisionKey
    ) {
        assertTrue(result is ArtworkDecisionLookupResult.MissingAuthoritative)
        result as ArtworkDecisionLookupResult.MissingAuthoritative
        assertEquals(key, result.decisionKey)
        assertTrue(result.loadState.isAuthoritativeForMissing())
    }

    private fun assertCacheNotAuthoritative(
        result: ArtworkDecisionLookupResult,
        key: ArtworkDecisionKey,
        reason: String?,
        errorClass: String? = null,
        requiredContext: ArtworkDecisionAuthorityContext? = null
    ) {
        assertTrue(result is ArtworkDecisionLookupResult.CacheNotAuthoritative)
        result as ArtworkDecisionLookupResult.CacheNotAuthoritative
        assertEquals(key, result.decisionKey)
        assertEquals(reason, result.reason)
        if (errorClass != null) {
            assertEquals(errorClass, result.errorClass)
        }
        assertFalse(result.loadState.isAuthoritativeForMissing(requiredContext))
    }
}
