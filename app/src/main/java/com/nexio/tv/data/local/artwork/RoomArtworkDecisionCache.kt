package com.nexio.tv.data.local.artwork

import com.google.gson.Gson
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionAuthorityContext
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionCacheDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionCacheSnapshotDiagnostics
import com.nexio.tv.core.artwork.ArtworkDecisionJsonCodec
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDecisionLookupResult
import com.nexio.tv.core.artwork.ArtworkDecisionStoreLoadState
import com.nexio.tv.core.artwork.artworkDecisionShortSha256
import com.nexio.tv.core.artwork.nonAuthoritativeReason
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class RoomArtworkDecisionCache(
    private val dao: ArtworkDecisionDao,
    private val migrationDao: ArtworkCacheMigrationDao,
    private val legacyFile: File,
    gson: Gson,
    private val fallback: ArtworkDecisionCache,
    @Suppress("unused") private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
    private val codec = ArtworkDecisionJsonCodec(gson)
    private val mapper = ArtworkDecisionEntityMapper(codec)
    private val lock = Any()
    private var loaded = false
    private var fallbackMode = false
    private var currentLoadState: ArtworkDecisionStoreLoadState = ArtworkDecisionStoreLoadState.NotLoaded
    private var droppedDecisionCount = 0
    private var quarantinedDecisionCount = 0
    private var lastLoadSuccess: Boolean? = null
    private var lastLoadReason: String? = null
    private var lastLoadErrorClass: String? = null
    private val decisions = linkedMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = linkedMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()
    private val authorityContext: ArtworkDecisionAuthorityContext
        get() = ArtworkDecisionAuthorityContext(
            storeIdHash = artworkDecisionShortSha256(legacyFile.absolutePath),
            schemaVersion = SCHEMA_VERSION,
            providerPolicyHash = null,
            settingsHash = null,
            credentialHash = null,
            imageLanguage = null
        )

    override fun get(key: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        if (fallbackMode) return@synchronized fallback.get(key)
        if (!ensureLoadedLocked()) return@synchronized fallback.get(key)
        decisions[key]
    }

    override fun lookup(
        key: ArtworkDecisionKey,
        requiredContext: ArtworkDecisionAuthorityContext?
    ): ArtworkDecisionLookupResult = synchronized(lock) {
        if (fallbackMode) return@synchronized fallback.lookup(key, requiredContext)
        runCatching {
            if (!ensureLoadedLocked()) return@runCatching fallback.lookup(key, requiredContext)
            decisions[key]?.let(ArtworkDecisionLookupResult::Found) ?: if (
                currentLoadState.isAuthoritativeForMissing(requiredContext)
            ) {
                ArtworkDecisionLookupResult.MissingAuthoritative(
                    decisionKey = key,
                    loadState = currentLoadState as ArtworkDecisionStoreLoadState.LoadedAuthoritative
                )
            } else {
                ArtworkDecisionLookupResult.CacheNotAuthoritative(
                    decisionKey = key,
                    loadState = currentLoadState,
                    reason = currentLoadState.nonAuthoritativeReason(requiredContext),
                    errorClass = (currentLoadState as? ArtworkDecisionStoreLoadState.FailedNonAuthoritative)
                        ?.errorClass
                )
            }
        }.getOrElse { error ->
            ArtworkDecisionLookupResult.LookupFailed(
                decisionKey = key,
                errorClass = error.javaClass.simpleName,
                messageHash = error.message?.let(::artworkDecisionShortSha256)
            )
        }
    }

    override fun loadState(): ArtworkDecisionStoreLoadState = synchronized(lock) {
        if (fallbackMode) return@synchronized fallback.loadState()
        if (!ensureLoadedLocked()) return@synchronized fallback.loadState()
        currentLoadState
    }

    override fun put(decision: ArtworkDecision) = synchronized(lock) {
        if (fallbackMode) {
            fallback.put(decision)
            return@synchronized
        }
        if (!ensureLoadedLocked()) {
            fallback.put(decision)
            return@synchronized
        }
        try {
            runBlocking(ioDispatcher) {
                dao.upsertDecision(mapper.toEntity(decision))
            }
        } catch (error: Exception) {
            enterFallbackModeLocked(error)
            fallback.put(decision)
            return@synchronized
        }
        decisions[decision.decisionKey] = decision
        refreshLoadedStateAfterMutationLocked()
    }

    override fun remove(key: ArtworkDecisionKey) = synchronized(lock) {
        if (fallbackMode) {
            fallback.remove(key)
            return@synchronized
        }
        if (!ensureLoadedLocked()) {
            fallback.remove(key)
            return@synchronized
        }
        try {
            runBlocking(ioDispatcher) {
                dao.deleteDecisionAndLinks(key.value)
            }
        } catch (error: Exception) {
            enterFallbackModeLocked(error)
            fallback.remove(key)
            return@synchronized
        }
        decisions.remove(key)
        removeLinksForLocked(setOf(key))
        refreshLoadedStateAfterMutationLocked()
    }

    override fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    ) = synchronized(lock) {
        if (fallbackMode) {
            fallback.linkPreviewToCanonical(previewKey, canonicalKey)
            return@synchronized
        }
        if (!ensureLoadedLocked()) {
            fallback.linkPreviewToCanonical(previewKey, canonicalKey)
            return@synchronized
        }
        try {
            runBlocking(ioDispatcher) {
                dao.upsertPreviewLink(ArtworkPreviewLinkEntity(previewKey.value, canonicalKey.value))
            }
        } catch (error: Exception) {
            enterFallbackModeLocked(error)
            fallback.linkPreviewToCanonical(previewKey, canonicalKey)
            return@synchronized
        }
        previewToCanonical[previewKey] = canonicalKey
        refreshLoadedStateAfterMutationLocked()
    }

    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        if (fallbackMode) return@synchronized fallback.getCanonicalForPreview(previewKey)
        if (!ensureLoadedLocked()) return@synchronized fallback.getCanonicalForPreview(previewKey)
        previewToCanonical[previewKey]?.let(decisions::get)
    }

    override fun invalidateBySettingsHash(settingsHash: String) = synchronized(lock) {
        invalidateMatchingLocked(
            matches = { decision -> decision.settingsHash == settingsHash },
            fallbackAction = { fallback.invalidateBySettingsHash(settingsHash) }
        )
    }

    override fun invalidateByCredentialHash(credentialHash: String) = synchronized(lock) {
        invalidateMatchingLocked(
            matches = { decision -> decision.credentialHash == credentialHash },
            fallbackAction = { fallback.invalidateByCredentialHash(credentialHash) }
        )
    }

    override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = synchronized(lock) {
        invalidateMatchingLocked(
            matches = { decision -> decision.settingsHash in settingsHashes || decision.credentialHash in credentialHashes },
            fallbackAction = { fallback.invalidateArtworkPolicy(settingsHashes, credentialHashes) }
        )
    }

    override fun invalidatePremiumArtworkPolicy() = synchronized(lock) {
        invalidateMatchingLocked(
            matches = { decision -> decision.settingsHash != null || decision.credentialHash != null },
            fallbackAction = fallback::invalidatePremiumArtworkPolicy
        )
    }

    override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics = synchronized(lock) {
        if (fallbackMode) {
            return@synchronized (fallback as? ArtworkDecisionCacheDiagnostics)?.snapshotDiagnostics()
                ?: ArtworkDecisionCacheSnapshotDiagnostics(
                    loaded = true,
                    decisionCount = 0,
                    linkCount = 0,
                    storeFilePresent = legacyFile.exists(),
                    storeFileReadable = legacyFile.exists() && legacyFile.canRead(),
                    storeFileBytes = legacyFile.takeIf { it.isFile }?.length(),
                    lastLoadSuccess = false,
                    lastLoadReason = "fallback",
                    lastLoadErrorClass = lastLoadErrorClass,
                    droppedDecisionCount = null,
                    loadStateName = "FailedNonAuthoritative",
                    authoritative = false,
                    schemaVersion = SCHEMA_VERSION,
                    storedSchemaVersion = null,
                    quarantinedDecisionCount = null,
                    authorityContext = authorityContext
                )
        }
        ArtworkDecisionCacheSnapshotDiagnostics(
            loaded = loaded,
            decisionCount = decisions.size,
            linkCount = previewToCanonical.size,
            storeFilePresent = legacyFile.exists(),
            storeFileReadable = legacyFile.exists() && legacyFile.canRead(),
            storeFileBytes = legacyFile.takeIf { it.isFile }?.length(),
            lastLoadSuccess = lastLoadSuccess,
            lastLoadReason = lastLoadReason,
            lastLoadErrorClass = lastLoadErrorClass,
            droppedDecisionCount = droppedDecisionCount,
            loadStateName = currentLoadState.nameForDiagnostics(),
            authoritative = currentLoadState.isAuthoritativeForMissing(),
            schemaVersion = SCHEMA_VERSION,
            storedSchemaVersion = SCHEMA_VERSION,
            quarantinedDecisionCount = quarantinedDecisionCount,
            authorityContext = authorityContext
        )
    }

    private fun ensureLoadedLocked(): Boolean {
        if (loaded) return !fallbackMode
        loaded = true
        currentLoadState = ArtworkDecisionStoreLoadState.Loading

        val loadedRows = try {
            runBlocking(ioDispatcher) {
                importLegacyJsonIfNeeded()
                dao.deleteLinksReferencingMissingDecisions()
                dao.getAllDecisions() to dao.getAllPreviewLinks()
            }
        } catch (error: Exception) {
            enterFallbackModeLocked(error)
            return false
        }

        decisions.clear()
        previewToCanonical.clear()
        val (decisionEntities, linkEntities) = loadedRows
        decisionEntities.forEach { entity ->
            val decision = mapper.toDomain(entity)
            if (decision == null) {
                droppedDecisionCount += 1
                quarantinedDecisionCount += 1
            } else {
                decisions[decision.decisionKey] = decision
            }
        }
        linkEntities.forEach { link ->
            previewToCanonical[ArtworkDecisionKey(link.previewKey)] = ArtworkDecisionKey(link.canonicalKey)
        }
        daoCleanupMissingLinksAfterLoad()
        currentLoadState =
            if (droppedDecisionCount == 0 && quarantinedDecisionCount == 0) {
                loadedAuthoritativeState()
            } else {
                loadedPartialNonAuthoritativeState()
            }
        lastLoadSuccess = true
        lastLoadReason = null
        lastLoadErrorClass = null
        return true
    }

    private fun daoCleanupMissingLinksAfterLoad() {
        val missingKeys = previewToCanonical
            .filter { (_, canonicalKey) -> canonicalKey !in decisions }
            .map { it.key }
        missingKeys.forEach(previewToCanonical::remove)
    }

    private suspend fun importLegacyJsonIfNeeded() {
        if (migrationDao.get(MIGRATION_KEY) != null) return

        val snapshot = codec.readStoreFile(legacyFile)
        val importedDecisions = snapshot?.decisions.orEmpty()
        val importedLinks = snapshot?.previewLinks.orEmpty()
        droppedDecisionCount = snapshot?.droppedDecisionCount ?: 0
        quarantinedDecisionCount = snapshot?.quarantinedDecisionCount ?: 0
        if (importedDecisions.isNotEmpty()) {
            dao.upsertDecisions(importedDecisions.map(mapper::toEntity))
        }
        if (importedLinks.isNotEmpty()) {
            dao.upsertPreviewLinks(
                importedLinks.map { (previewKey, canonicalKey) ->
                    ArtworkPreviewLinkEntity(previewKey.value, canonicalKey.value)
                }
            )
        }
        migrationDao.markComplete(
            ArtworkCacheMigrationEntity(
                key = MIGRATION_KEY,
                completedAtMs = System.currentTimeMillis(),
                importedCount = importedDecisions.size,
                skippedCount = snapshot?.quarantinedDecisionCount ?: 0
            )
        )
    }

    private fun invalidateMatchingLocked(
        matches: (ArtworkDecision) -> Boolean,
        fallbackAction: () -> Unit
    ) {
        if (fallbackMode) {
            fallbackAction()
            return
        }
        if (!ensureLoadedLocked()) {
            fallbackAction()
            return
        }
        val deletedKeys = decisions.values
            .filter(matches)
            .mapTo(mutableSetOf()) { decision -> decision.decisionKey }
        if (deletedKeys.isEmpty()) return

        try {
            runBlocking(ioDispatcher) {
                deletedKeys.forEach { key -> dao.deleteDecisionAndLinks(key.value) }
            }
        } catch (error: Exception) {
            enterFallbackModeLocked(error)
            fallbackAction()
            return
        }

        deletedKeys.forEach(decisions::remove)
        removeLinksForLocked(deletedKeys)
        refreshLoadedStateAfterMutationLocked()
    }

    private fun removeLinksForLocked(keys: Set<ArtworkDecisionKey>) {
        val links = previewToCanonical.iterator()
        while (links.hasNext()) {
            val (previewKey, canonicalKey) = links.next()
            if (previewKey in keys || canonicalKey in keys) {
                links.remove()
            }
        }
    }

    private fun refreshLoadedStateAfterMutationLocked() {
        currentLoadState =
            if (droppedDecisionCount == 0 && quarantinedDecisionCount == 0) {
                loadedAuthoritativeState()
            } else {
                loadedPartialNonAuthoritativeState()
            }
    }

    private fun enterFallbackModeLocked(error: Exception) {
        decisions.clear()
        previewToCanonical.clear()
        fallbackMode = true
        loaded = true
        lastLoadSuccess = false
        lastLoadReason = "exception"
        lastLoadErrorClass = error.javaClass.simpleName
        currentLoadState = ArtworkDecisionStoreLoadState.FailedNonAuthoritative(
            errorClass = error.javaClass.simpleName,
            errorMessageHash = error.message?.let(::artworkDecisionShortSha256),
            errorTopFrame = error.stackTrace.firstOrNull()?.let { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            },
            authorityContext = authorityContext
        )
    }

    private fun loadedAuthoritativeState(): ArtworkDecisionStoreLoadState.LoadedAuthoritative =
        ArtworkDecisionStoreLoadState.LoadedAuthoritative(
            decisionCount = decisions.size,
            droppedDecisionCount = droppedDecisionCount,
            quarantinedDecisionCount = quarantinedDecisionCount,
            schemaVersion = SCHEMA_VERSION,
            storedSchemaVersion = SCHEMA_VERSION,
            authorityContext = authorityContext
        )

    private fun loadedPartialNonAuthoritativeState(): ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative =
        ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative(
            decisionCount = decisions.size,
            droppedDecisionCount = droppedDecisionCount,
            quarantinedDecisionCount = quarantinedDecisionCount,
            schemaVersion = SCHEMA_VERSION,
            storedSchemaVersion = SCHEMA_VERSION,
            authorityContext = authorityContext,
            errorClass = lastLoadErrorClass
        )

    private fun ArtworkDecisionStoreLoadState.nameForDiagnostics(): String = when (this) {
        ArtworkDecisionStoreLoadState.NotLoaded -> "NotLoaded"
        ArtworkDecisionStoreLoadState.Loading -> "Loading"
        is ArtworkDecisionStoreLoadState.LoadedAuthoritative -> "LoadedAuthoritative"
        is ArtworkDecisionStoreLoadState.LoadedPartialNonAuthoritative -> "LoadedPartialNonAuthoritative"
        is ArtworkDecisionStoreLoadState.FailedNonAuthoritative -> "FailedNonAuthoritative"
    }

    private companion object {
        const val MIGRATION_KEY = "decisions-json-v1"
        const val SCHEMA_VERSION = ArtworkDecisionJsonCodec.SCHEMA_VERSION
    }
}
