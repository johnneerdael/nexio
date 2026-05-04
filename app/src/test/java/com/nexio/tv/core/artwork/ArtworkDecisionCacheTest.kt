package com.nexio.tv.core.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
