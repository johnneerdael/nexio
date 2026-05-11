package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.domain.model.strictlyContains
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val version = MutableStateFlow(0L)
    private val staleItemKeys = MutableStateFlow<Set<String>>(emptySet())

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
        val payload = JsonObject().apply {
            add("value", gson.toJsonTree(overlay))
            addProperty("schemaVersion", OVERLAY_SCHEMA_VERSION)
        }
        val normalizedAliases = (aliases + overlay.itemKey).normalizedItemKeys()
        val editor = prefs().edit()
            .putString(overlayPrefsKey(overlay.overlayKey), gson.toJson(payload))

        normalizedAliases.forEach { itemKey ->
            editor.putString(
                aliasPrefsKey(
                    itemKey = itemKey,
                    languageTag = overlay.languageTag,
                    policyVersion = overlay.policyVersion
                ),
                overlay.overlayKey
            )
        }

        editor.apply()
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
        val editor = prefs().edit()
        val normalized = itemKeys.normalizedItemKeys()
        normalized.forEach { itemKey ->
            editor.remove(
                aliasPrefsKey(
                    itemKey = itemKey,
                    languageTag = languageTag,
                    policyVersion = policyVersion
                )
            )
        }
        editor.apply()
        if (staleItemKeys.value.isNotEmpty()) {
            staleItemKeys.update { current ->
                if (current.isEmpty()) current else current - normalized
            }
        }
        incrementVersion()
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            val sharedPreferences = prefs()
            val overlayKeys = sharedPreferences.all.keys
                .filter { key -> key.startsWith(OVERLAY_PREFIX) || key.startsWith(ALIAS_PREFIX) }
            if (overlayKeys.isEmpty()) return@withContext

            val editor = sharedPreferences.edit()
            overlayKeys.forEach(editor::remove)
            editor.apply()
            staleItemKeys.value = emptySet()
            incrementVersion()
        }
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
        staleItemKeys.update { current -> current + trimmed }
        incrementVersion()
    }

    /**
     * In-memory mark-all-stale. Every overlay alias currently persisted to
     * SharedPreferences is added to staleItemKeys. Used by
     * ArtworkSettingsInvalidator (Task 14) when the settings signature changes.
     *
     * Not persisted (matches markStaleIfWeakerIds semantics).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun markStaleAll(reason: String) {
        val itemKeys = withContext(Dispatchers.IO) {
            prefs().all.keys
                .asSequence()
                .filter { it.startsWith(ALIAS_PREFIX) }
                .mapNotNull { extractItemKeyFromAliasPrefsKey(it) }
                .toSet()
        }
        if (itemKeys.isEmpty()) return
        staleItemKeys.update { current -> current + itemKeys }
        incrementVersion()
    }

    fun readForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, HydratedHomeOverlay> {
        val sharedPreferences = prefs()
        return itemKeys.normalizedItemKeys().mapNotNull { itemKey ->
            val overlayKey = sharedPreferences.getString(
                aliasPrefsKey(
                    itemKey = itemKey,
                    languageTag = languageTag,
                    policyVersion = policyVersion
                ),
                null
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
        val stale = staleItemKeys.value.any { staleKey ->
            prefs().getString(aliasPrefsKey(staleKey, languageTag, policyVersion), null) ==
                overlay.overlayKey
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
            val raw = prefs().getString(overlayPrefsKey(overlayKey), null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
            if (schemaVersion != OVERLAY_SCHEMA_VERSION) return null
            val overlay = gson.fromJson(root.get("value"), HydratedHomeOverlay::class.java) ?: return null
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

    private fun incrementVersion() {
        version.update { it + 1 }
    }

    private fun overlayPrefsKey(overlayKey: String): String = "$OVERLAY_PREFIX${overlayKey.trim()}"

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

    private fun readOverlayForItemKey(
        itemKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): HydratedHomeOverlay? {
        val sharedPreferences = prefs()
        // Try matching across all stored language tags + policy versions for this
        // itemKey. In practice the store currently only ever holds languageTag=current
        // AND policyVersion=DEFAULT_HOME_OVERLAY_POLICY_VERSION, but we don't have
        // those values here — walk the keyspace to find any alias for this itemKey.
        val trimmedItemKey = itemKey.trim()
        val matchingAliasKey = sharedPreferences.all.keys.firstOrNull { key ->
            key.startsWith(ALIAS_PREFIX) && key.endsWith("::$trimmedItemKey")
        } ?: return null
        val overlayKey = sharedPreferences.getString(matchingAliasKey, null) ?: return null
        return readOverlayByKey(overlayKey = overlayKey, nowMs = nowMs)
    }

    private fun HydratedHomeOverlay.applyInMemoryStaleness(itemKey: String): HydratedHomeOverlay {
        if (state == HomeItemHydrationState.STALE_READY) return this
        if (itemKey.trim() !in staleItemKeys.value) return this
        return copy(state = HomeItemHydrationState.STALE_READY)
    }

    private fun Set<String>.normalizedItemKeys(): Set<String> =
        mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()

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
