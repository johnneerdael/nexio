package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.domain.model.strictlyContains
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@Singleton
class HydratedHomeOverlayStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    private val gson = Gson()
    private val version = MutableStateFlow(0L)
    private val staleItemKeys = MutableStateFlow<Set<String>>(emptySet())
    private val entryStore by lazy {
        HydratedHomeOverlayTypedStore(
            file = File(context.filesDir, "hydrated-home-overlay-v2/entries.json"),
            gson = gson
        ).also { store ->
            migrateV1FileIfNeeded(store)
            migrateLegacyPrefsIfNeeded(store)
        }
    }

    fun observeForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ): Flow<Map<String, HydratedHomeOverlay>> {
        val normalizedKeys = itemKeys.normalizedItemKeys()
        // Cold-start hydration writes ~60 overlays in quick succession; without debounce
        // every write bumps `version`, every consumer re-reads the entire alias index from
        // disk, and the resulting Map<String, HydratedHomeOverlay> allocations swamp the
        // GC (observed: 21 concurrent GCs in 35s, heap to 113MB). Debouncing the version
        // flow coalesces bursty puts into 1-3 reads at the cost of a small publish delay.
        return version
            .debounce(VERSION_DEBOUNCE_MS)
            .map {
                withContext(Dispatchers.IO) {
                    readForItemKeys(
                        itemKeys = normalizedKeys,
                        languageTag = languageTag,
                        policyVersion = policyVersion
                    )
                }
            }
    }

    suspend fun upsert(
        overlay: HydratedHomeOverlay,
        aliases: Set<String>
    ) {
        val normalizedAliases = (aliases + overlay.itemKey).normalizedItemKeys()
        val aliasKeys = normalizedAliases.map { itemKey ->
                aliasPrefsKey(
                    itemKey = itemKey,
                    languageTag = overlay.languageTag,
                    policyVersion = overlay.policyVersion
                )
        }.toSet()

        val stored = withContext(Dispatchers.IO) {
            entryStore.upsert(overlay, aliasKeys)
        }
        if (!stored) return
        // Upsert replaces stale state — clear all alias itemKeys we just persisted.
        if (staleItemKeys.value.isNotEmpty()) {
            staleItemKeys.update { current ->
                if (current.isEmpty()) current else current - normalizedAliases
            }
        }
        incrementVersion()
    }

    suspend fun removeAliases(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ) {
        val normalized = itemKeys.normalizedItemKeys()
        if (normalized.isEmpty()) return
        val removedAll = withContext(Dispatchers.IO) {
            val aliasKeys = normalized.map { itemKey ->
                aliasPrefsKey(
                    itemKey = itemKey,
                    languageTag = languageTag,
                    policyVersion = policyVersion
                )
            }
            entryStore.removeAliases(aliasKeys)
        }
        if (!removedAll) return
        if (staleItemKeys.value.isNotEmpty()) {
            staleItemKeys.update { current ->
                if (current.isEmpty()) current else current - normalized
            }
        }
        incrementVersion()
    }

    suspend fun clearAll() {
        val removedAll = withContext(Dispatchers.IO) { entryStore.clearAll() }
        if (!removedAll) return
        staleItemKeys.value = emptySet()
        incrementVersion()
    }

    /**
     * In-memory mark-stale. If the overlay for [itemKey] has a stable-IDs snapshot
     * that [currentIds] strictly contains (i.e., current has at least one ID the
     * snapshot lacked and lost none of the IDs the snapshot had), the next read
     * for this itemKey returns the overlay with state = STALE_READY.
     *
     * Not persisted. Cold-start re-loads overlays in their persisted state; the
     * cross-id enricher (Task 13) re-fires markStaleIfWeakerIds for any item whose
     * current IDs are still strictly broader than the snapshot, naturally
     * re-marking it.
     */
    suspend fun markStaleIfWeakerIds(itemKey: String, currentIds: ProviderIds) {
        val trimmed = itemKey.trim().takeIf { it.isNotEmpty() } ?: return
        val overlay = withContext(Dispatchers.IO) {
            readOverlayForItemKey(itemKey = trimmed)
        } ?: return
        if (overlay.state == HomeItemHydrationState.STALE_READY) return
        if (!currentIds.strictlyContains(overlay.stableIdsSnapshot)) return
        if (trimmed in staleItemKeys.value) return  // already marked — avoid redundant version bump
        staleItemKeys.update { current -> if (trimmed in current) current else current + trimmed }
        traceEvents.emitOverlayStaleMarked(
            itemKey = trimmed,
            reason = "cross_id_enriched",
            oldState = overlay.state.name
        )
        incrementVersion()
    }

    /**
     * In-memory mark-all-stale. Every overlay alias currently persisted to
     * the file-backed entry store is added to staleItemKeys. Used by
     * ArtworkSettingsInvalidator (Task 14) when the settings signature changes.
     *
     * Not persisted (matches markStaleIfWeakerIds semantics).
     */
    suspend fun markStaleAll(reason: String) {
        val itemKeys = withContext(Dispatchers.IO) {
            entryStore.aliasKeys()
                .asSequence()
                .mapNotNull { extractItemKeyFromAliasPrefsKey(it) }
                .toSet()
        }
        if (itemKeys.isEmpty()) return
        staleItemKeys.update { current -> current + itemKeys }
        // Pragmatic compaction: one event for the entire batch rather than one per
        // itemKey. markStaleAll fires on settings-signature changes, which can
        // affect every persisted overlay (~hundreds), and the per-item detail isn't
        // useful for the "popping watchdog" signal anyway.
        traceEvents.emitOverlayStaleMarked(
            itemKey = "<all:${itemKeys.size}>",
            reason = reason.ifBlank { "settings_change" },
            oldState = "CANONICAL_READY"
        )
        incrementVersion()
    }

    fun readForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, HydratedHomeOverlay> {
        return itemKeys.normalizedItemKeys().mapNotNull { itemKey ->
            val overlayKey = readAliasOverlayKey(
                itemKey = itemKey,
                languageTag = languageTag,
                policyVersion = policyVersion
            ) ?: return@mapNotNull null
            val overlay = readOverlayByKey(
                overlayKey = overlayKey,
                expectedLanguageTag = languageTag,
                expectedPolicyVersion = policyVersion,
                nowMs = nowMs
            ) ?: return@mapNotNull null

            itemKey to overlay.applyInMemoryStaleness(itemKey)
        }.toMap()
    }

    fun readByCanonicalIdentity(
        canonicalProvider: ProviderId,
        canonicalId: String,
        contentType: ContentType,
        languageTag: String,
        policyVersion: Int,
        nowMs: Long = System.currentTimeMillis()
    ): HydratedHomeOverlay? {
        val overlayKey = hydratedHomeOverlayKey(
            canonicalProvider = canonicalProvider,
            canonicalId = canonicalId,
            contentType = contentType,
            languageTag = languageTag,
            policyVersion = policyVersion
        )
        val overlay = readOverlayByKey(
            overlayKey = overlayKey,
            expectedCanonicalProvider = canonicalProvider,
            expectedCanonicalId = canonicalId,
            expectedContentType = contentType,
            expectedLanguageTag = languageTag,
            expectedPolicyVersion = policyVersion,
            nowMs = nowMs
        ) ?: return null
        // Stale-state lookup keyed by the row item key isn't possible here (we have the
        // canonical identity, not the row alias), so we walk the staleItemKeys set looking
        // for ANY itemKey whose stored alias resolves to this overlayKey. Cheap when set
        // is empty (common steady state) or small (typical post-invalidation).
        val currentStale = staleItemKeys.value
        val stale = if (currentStale.isEmpty()) {
            false
        } else {
            currentStale.any { staleKey ->
                readAliasOverlayKey(staleKey, languageTag, policyVersion) == overlay.overlayKey
            }
        }
        return if (stale) overlay.copy(state = HomeItemHydrationState.STALE_READY) else overlay
    }

    private fun readOverlayByKey(
        overlayKey: String,
        expectedCanonicalProvider: ProviderId? = null,
        expectedCanonicalId: String? = null,
        expectedContentType: ContentType? = null,
        expectedLanguageTag: String? = null,
        expectedPolicyVersion: Int? = null,
        nowMs: Long
    ): HydratedHomeOverlay? {
        return runCatching {
            val overlay = entryStore.overlay(overlayKey)?.normalizeDefaults() ?: return null
            if (!overlay.isValidFor(
                    overlayKey = overlayKey,
                    expectedCanonicalProvider = expectedCanonicalProvider,
                    expectedCanonicalId = expectedCanonicalId,
                    expectedContentType = expectedContentType,
                    expectedLanguageTag = expectedLanguageTag,
                    expectedPolicyVersion = expectedPolicyVersion
                )
            ) {
                return null
            }

            overlay.takeUnless { it.isExpired(nowMs) }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read hydrated home overlay key=$overlayKey", error)
        }.getOrNull()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun v1EntriesFile(): File =
        File(context.filesDir, "hydrated-home-overlay-v1/entries.json")

    private fun migrateV1FileIfNeeded(store: HydratedHomeOverlayTypedStore) {
        val v1File = v1EntriesFile()
        if (!v1File.exists()) return
        if (store.migrateFromV1File(v1File)) {
            v1File.delete()
        }
    }

    private fun migrateLegacyPrefsIfNeeded(store: HydratedHomeOverlayTypedStore) {
        val legacy = prefs()
        val values = legacy.all
        if (values.isEmpty()) return

        val legacyKeysToClear = linkedSetOf<String>()
        for ((key, value) in values) {
            val raw = value as? String ?: continue
            if (raw.isBlank()) continue
            if (key.startsWith(OVERLAY_PREFIX) || key.startsWith(ALIAS_PREFIX)) {
                legacyKeysToClear += key
            }
        }
        if (legacyKeysToClear.isEmpty()) return
        if (!store.migrateFromLegacyPrefsEntries(values)) return

        val editor = legacy.edit()
        for (key in legacyKeysToClear) {
            editor.remove(key)
        }
        editor.commit()
    }

    private fun incrementVersion() {
        version.update { it + 1 }
    }

    private fun aliasPrefsKey(
        itemKey: String,
        languageTag: String,
        policyVersion: Int
    ): String = "$ALIAS_PREFIX${languageTag.trim()}::policy:$policyVersion::${itemKey.trim()}"

    /**
     * Inverse of [aliasPrefsKey]. Returns the original itemKey component for the
     * stored alias key, or null if the key doesn't match the expected shape.
     *
     * Stored shape: "alias::$languageTag::policy:$policyVersion::$itemKey"
     */
    private fun extractItemKeyFromAliasPrefsKey(prefsKey: String): String? {
        if (!prefsKey.startsWith(ALIAS_PREFIX)) return null
        // The itemKey itself can contain colons (e.g. "movie:tmdb:550") but never the
        // "::" double-colon used as our delimiter, so we walk by "::" separators.
        val withoutPrefix = prefsKey.substring(ALIAS_PREFIX.length)
        val firstSep = withoutPrefix.indexOf("::").takeIf { it >= 0 } ?: return null
        val afterLanguage = withoutPrefix.substring(firstSep + 2)
        if (!afterLanguage.startsWith("policy:")) return null
        val secondSep = afterLanguage.indexOf("::").takeIf { it >= 0 } ?: return null
        val itemKey = afterLanguage.substring(secondSep + 2)
        return itemKey.trim().takeIf { it.isNotEmpty() }
    }

    private fun readAliasOverlayKey(
        itemKey: String,
        languageTag: String,
        policyVersion: Int
    ): String? = readAliasOverlayKey(
        aliasPrefsKey(
            itemKey = itemKey,
            languageTag = languageTag,
            policyVersion = policyVersion
        )
    )

    private fun readAliasOverlayKey(aliasKey: String): String? {
        return entryStore.aliasOverlayKey(aliasKey)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun readOverlayForItemKey(
        itemKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): HydratedHomeOverlay? {
        // Try matching across all stored language tags + policy versions for this
        // itemKey. In practice the store currently only ever holds languageTag=current
        // AND policyVersion=DEFAULT_HOME_OVERLAY_POLICY_VERSION, but we don't have
        // those values here — walk the keyspace to find any alias for this itemKey.
        val trimmedItemKey = itemKey.trim()
        val matchingAliasKey = entryStore.aliasKeys().firstOrNull { key ->
            key.startsWith(ALIAS_PREFIX) && key.endsWith("::$trimmedItemKey")
        } ?: return null
        val overlayKey = readAliasOverlayKey(matchingAliasKey) ?: return null
        return readOverlayByKey(overlayKey = overlayKey, nowMs = nowMs)
    }

    private fun HydratedHomeOverlay.applyInMemoryStaleness(itemKey: String): HydratedHomeOverlay {
        if (state == HomeItemHydrationState.STALE_READY) return this
        if (itemKey.trim() !in staleItemKeys.value) return this
        return copy(state = HomeItemHydrationState.STALE_READY)
    }

    private fun Set<String>.normalizedItemKeys(): Set<String> =
        mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()

    /**
     * Self-heals records persisted before Task 6 added stableIdsSnapshot /
     * settingsSignature to [HydratedHomeOverlay]. Gson's reflection-based
     * deserializer bypasses Kotlin constructors and leaves missing non-null
     * fields as JVM nulls, which would NPE downstream (markStaleIfWeakerIds,
     * ArtworkSettingsInvalidator comparisons). Restore the non-null contract
     * by substituting the Kotlin-declared defaults.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun HydratedHomeOverlay.normalizeDefaults(): HydratedHomeOverlay {
        val needsSnapshot = stableIdsSnapshot == null
        val needsSignature = settingsSignature == null
        if (!needsSnapshot && !needsSignature) return this
        return copy(
            stableIdsSnapshot = if (needsSnapshot) ProviderIds() else stableIdsSnapshot,
            settingsSignature = if (needsSignature) "" else settingsSignature
        )
    }

    private fun HydratedHomeOverlay.isValidFor(
        overlayKey: String,
        expectedCanonicalProvider: ProviderId?,
        expectedCanonicalId: String?,
        expectedContentType: ContentType?,
        expectedLanguageTag: String?,
        expectedPolicyVersion: Int?
    ): Boolean {
        if (this.overlayKey != overlayKey) return false
        if (!hasCanonicalIdentity()) return false
        if (canonicalOverlayKey() != overlayKey) return false
        if (displayHash != fields.hydratedHomeDisplayHash()) return false
        if (expectedCanonicalProvider != null && canonicalProvider != expectedCanonicalProvider) return false
        if (expectedCanonicalId != null && canonicalId.trim() != expectedCanonicalId.trim()) return false
        if (expectedContentType != null && contentType != expectedContentType) return false
        if (expectedLanguageTag != null && languageTag.trim() != expectedLanguageTag.trim()) return false
        if (expectedPolicyVersion != null && policyVersion != expectedPolicyVersion) return false

        return true
    }

    private fun HydratedHomeOverlay.hasCanonicalIdentity(): Boolean =
        canonicalId.isNotBlank() && languageTag.isNotBlank()

    private fun HydratedHomeOverlay.canonicalOverlayKey(): String =
        hydratedHomeOverlayKey(
            canonicalProvider = canonicalProvider,
            canonicalId = canonicalId,
            contentType = contentType,
            languageTag = languageTag,
            policyVersion = policyVersion
        )

    private companion object {
        const val TAG = "HydratedHomeOverlayStore"
        const val PREFS_NAME = "hydrated_home_overlay_v1"
        const val OVERLAY_PREFIX = "overlay::"
        const val ALIAS_PREFIX = "alias::"
        const val OVERLAY_SCHEMA_VERSION = 1
        const val VERSION_DEBOUNCE_MS = 50L
    }
}
