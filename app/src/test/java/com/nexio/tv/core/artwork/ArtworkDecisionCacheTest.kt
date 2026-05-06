package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkDecisionCacheTest {
    private val cache = InMemoryArtworkDecisionCache()

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
        val cache = DurableArtworkDecisionCache(file = unwritableTarget, gson = Gson())
        val decision = durableRpdbDecision()

        try {
            cache.put(decision)
        } catch (error: Exception) {
            fail("put should not throw when durable persistence fails: $error")
        }

        assertEquals(decision, cache.get(decision.decisionKey))
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
}
