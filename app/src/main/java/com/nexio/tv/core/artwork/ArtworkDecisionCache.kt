package com.nexio.tv.core.artwork

interface ArtworkDecisionCache {
    fun get(key: ArtworkDecisionKey): ArtworkDecision?
    fun put(decision: ArtworkDecision)
    fun remove(key: ArtworkDecisionKey)
    fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    )
    fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision?
    fun invalidateBySettingsHash(settingsHash: String)
    fun invalidateByCredentialHash(credentialHash: String)
    fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>)
    fun invalidatePremiumArtworkPolicy()
}

interface ArtworkDecisionCacheDiagnostics {
    fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics
}

data class ArtworkDecisionCacheSnapshotDiagnostics(
    val loaded: Boolean,
    val decisionCount: Int,
    val linkCount: Int,
    val storeFilePresent: Boolean?,
    val storeFileReadable: Boolean?,
    val storeFileBytes: Long?,
    val lastLoadSuccess: Boolean?,
    val lastLoadReason: String?,
    val lastLoadErrorClass: String?,
    val droppedDecisionCount: Int?
)

class InMemoryArtworkDecisionCache : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
    private val decisions = mutableMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = mutableMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()

    @Synchronized
    override fun get(key: ArtworkDecisionKey): ArtworkDecision? =
        decisions[key]

    @Synchronized
    override fun put(decision: ArtworkDecision) {
        decisions[decision.decisionKey] = decision
    }

    @Synchronized
    override fun remove(key: ArtworkDecisionKey) {
        decisions.remove(key)
        val links = previewToCanonical.iterator()
        while (links.hasNext()) {
            val (previewKey, canonicalKey) = links.next()
            if (previewKey == key || canonicalKey == key) {
                links.remove()
            }
        }
    }

    @Synchronized
    override fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    ) {
        previewToCanonical[previewKey] = canonicalKey
    }

    @Synchronized
    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? =
        previewToCanonical[previewKey]?.let { canonicalKey -> decisions[canonicalKey] }

    @Synchronized
    override fun invalidateBySettingsHash(settingsHash: String) {
        invalidateMatching { decision -> decision.settingsHash == settingsHash }
    }

    @Synchronized
    override fun invalidateByCredentialHash(credentialHash: String) {
        invalidateMatching { decision -> decision.credentialHash == credentialHash }
    }

    @Synchronized
    override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) {
        invalidateMatching { decision ->
            decision.settingsHash in settingsHashes ||
                decision.credentialHash in credentialHashes
        }
    }

    @Synchronized
    override fun invalidatePremiumArtworkPolicy() {
        invalidateMatching { decision ->
            decision.settingsHash != null || decision.credentialHash != null
        }
    }

    private fun invalidateMatching(matches: (ArtworkDecision) -> Boolean) {
        val deletedKeys = decisions.values
            .filter(matches)
            .mapTo(mutableSetOf()) { decision -> decision.decisionKey }

        if (deletedKeys.isEmpty()) return

        deletedKeys.forEach { key -> decisions.remove(key) }

        val links = previewToCanonical.iterator()
        while (links.hasNext()) {
            val (previewKey, canonicalKey) = links.next()
            if (previewKey in deletedKeys || canonicalKey in deletedKeys) {
                links.remove()
            }
        }
    }

    @Synchronized
    override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics =
        ArtworkDecisionCacheSnapshotDiagnostics(
            loaded = true,
            decisionCount = decisions.size,
            linkCount = previewToCanonical.size,
            storeFilePresent = null,
            storeFileReadable = null,
            storeFileBytes = null,
            lastLoadSuccess = true,
            lastLoadReason = null,
            lastLoadErrorClass = null,
            droppedDecisionCount = 0
        )
}
