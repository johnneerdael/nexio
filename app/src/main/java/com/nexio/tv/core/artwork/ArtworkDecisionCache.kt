package com.nexio.tv.core.artwork

import com.nexio.tv.core.util.toHexLowercase

data class ArtworkDecisionAuthorityContext(
    val storeIdHash: String,
    val schemaVersion: Int,
    val providerPolicyHash: String?,
    val settingsHash: String?,
    val credentialHash: String?,
    val imageLanguage: String?
)

sealed class ArtworkDecisionStoreLoadState {
    object NotLoaded : ArtworkDecisionStoreLoadState()
    object Loading : ArtworkDecisionStoreLoadState()
    data class LoadedAuthoritative(
        val decisionCount: Int,
        val droppedDecisionCount: Int = 0,
        val quarantinedDecisionCount: Int = 0,
        val schemaVersion: Int? = null,
        val storedSchemaVersion: Int? = null,
        val authorityContext: ArtworkDecisionAuthorityContext? = null
    ) : ArtworkDecisionStoreLoadState()
    data class LoadedPartialNonAuthoritative(
        val decisionCount: Int,
        val droppedDecisionCount: Int = 0,
        val quarantinedDecisionCount: Int = 0,
        val schemaVersion: Int? = null,
        val storedSchemaVersion: Int? = null,
        val authorityContext: ArtworkDecisionAuthorityContext? = null,
        val errorClass: String? = null,
        val errorMessageHash: String? = null,
        val errorTopFrame: String? = null,
        val firstQuarantinedDecisionKeyHash: String? = null
    ) : ArtworkDecisionStoreLoadState()
    data class FailedNonAuthoritative(
        val errorClass: String,
        val errorMessageHash: String?,
        val errorTopFrame: String?,
        val authorityContext: ArtworkDecisionAuthorityContext? = null,
    ) : ArtworkDecisionStoreLoadState()

    fun isAuthoritativeForMissing(
        requiredContext: ArtworkDecisionAuthorityContext? = null
    ): Boolean =
        this is LoadedAuthoritative &&
            droppedDecisionCount == 0 &&
            quarantinedDecisionCount == 0 &&
            (requiredContext == null || authorityContext == requiredContext)
}

sealed class ArtworkDecisionLookupResult {
    data class Found(val decision: ArtworkDecision) : ArtworkDecisionLookupResult()
    data class MissingAuthoritative(
        val decisionKey: ArtworkDecisionKey,
        val loadState: ArtworkDecisionStoreLoadState.LoadedAuthoritative
    ) : ArtworkDecisionLookupResult()
    data class CacheNotAuthoritative(
        val decisionKey: ArtworkDecisionKey,
        val loadState: ArtworkDecisionStoreLoadState,
        val reason: String?,
        val errorClass: String?
    ) : ArtworkDecisionLookupResult()
    data class LookupFailed(
        val decisionKey: ArtworkDecisionKey,
        val errorClass: String,
        val messageHash: String?
    ) : ArtworkDecisionLookupResult()
}

interface ArtworkDecisionCache {
    fun get(key: ArtworkDecisionKey): ArtworkDecision?
    fun lookup(
        key: ArtworkDecisionKey,
        requiredContext: ArtworkDecisionAuthorityContext? = null
    ): ArtworkDecisionLookupResult =
        runCatching {
            val loadState = loadState()
            get(key)?.let(ArtworkDecisionLookupResult::Found)
                ?: if (loadState.isAuthoritativeForMissing(requiredContext)) {
                    ArtworkDecisionLookupResult.MissingAuthoritative(
                        decisionKey = key,
                        loadState = loadState as ArtworkDecisionStoreLoadState.LoadedAuthoritative
                    )
                } else {
                    ArtworkDecisionLookupResult.CacheNotAuthoritative(
                        decisionKey = key,
                        loadState = loadState,
                        reason = loadState.nonAuthoritativeReason(requiredContext),
                        errorClass = (loadState as? ArtworkDecisionStoreLoadState.FailedNonAuthoritative)?.errorClass
                    )
                }
        }.getOrElse { error ->
            ArtworkDecisionLookupResult.LookupFailed(
                decisionKey = key,
                errorClass = error.javaClass.simpleName,
                messageHash = error.message?.let(::artworkDecisionShortSha256)
            )
        }
    fun loadState(): ArtworkDecisionStoreLoadState =
        ArtworkDecisionStoreLoadState.LoadedAuthoritative(
            decisionCount = 0,
            droppedDecisionCount = 0,
            quarantinedDecisionCount = 0,
            schemaVersion = null,
            storedSchemaVersion = null,
            authorityContext = null
        )
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
    val droppedDecisionCount: Int?,
    val loadStateName: String = if (loaded) "LoadedAuthoritative" else "NotLoaded",
    val authoritative: Boolean = loaded && droppedDecisionCount == 0,
    val schemaVersion: Int? = null,
    val storedSchemaVersion: Int? = null,
    val quarantinedDecisionCount: Int? = 0,
    val errorMessageHash: String? = null,
    val errorTopFrame: String? = null,
    val firstQuarantinedDecisionKeyHash: String? = null,
    val authorityContext: ArtworkDecisionAuthorityContext? = null
)

class InMemoryArtworkDecisionCache : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
    private val decisions = mutableMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = mutableMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()

    @Synchronized
    override fun get(key: ArtworkDecisionKey): ArtworkDecision? =
        decisions[key]

    @Synchronized
    override fun lookup(
        key: ArtworkDecisionKey,
        requiredContext: ArtworkDecisionAuthorityContext?
    ): ArtworkDecisionLookupResult =
        decisions[key]?.let(ArtworkDecisionLookupResult::Found) ?: run {
            val loadState = loadState()
            if (loadState.isAuthoritativeForMissing(requiredContext)) {
                ArtworkDecisionLookupResult.MissingAuthoritative(
                    decisionKey = key,
                    loadState = loadState as ArtworkDecisionStoreLoadState.LoadedAuthoritative
                )
            } else {
                ArtworkDecisionLookupResult.CacheNotAuthoritative(
                    decisionKey = key,
                    loadState = loadState,
                    reason = loadState.nonAuthoritativeReason(requiredContext),
                    errorClass = null
                )
            }
        }

    override fun loadState(): ArtworkDecisionStoreLoadState =
        ArtworkDecisionStoreLoadState.LoadedAuthoritative(
            decisionCount = decisions.size,
            droppedDecisionCount = 0,
            quarantinedDecisionCount = 0,
            schemaVersion = null,
            storedSchemaVersion = null,
            authorityContext = null
        )

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
            droppedDecisionCount = 0,
            loadStateName = "LoadedAuthoritative",
            authoritative = true,
            schemaVersion = null,
            storedSchemaVersion = null,
            quarantinedDecisionCount = 0,
            errorMessageHash = null,
            errorTopFrame = null,
            firstQuarantinedDecisionKeyHash = null,
            authorityContext = null
        )
}

internal fun artworkDecisionShortSha256(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHexLowercase()
        .take(12)

internal fun ArtworkDecisionStoreLoadState.nonAuthoritativeReason(
    requiredContext: ArtworkDecisionAuthorityContext? = null
): String? = when (this) {
    ArtworkDecisionStoreLoadState.NotLoaded -> "not_loaded"
    ArtworkDecisionStoreLoadState.Loading -> "loading"
    is ArtworkDecisionStoreLoadState.LoadedAuthoritative ->
        if (requiredContext != null && authorityContext != requiredContext) "authority_context_mismatch" else null
    is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative -> "partial_load"
    is ArtworkDecisionStoreLoadState.FailedNonAuthoritative -> "load_failed"
}
