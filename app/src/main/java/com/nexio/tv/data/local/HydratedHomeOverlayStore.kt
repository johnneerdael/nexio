package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class HydratedHomeOverlayStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val version = MutableStateFlow(0L)

    fun observeForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ): Flow<Map<String, HydratedHomeOverlay>> {
        val normalizedKeys = itemKeys.normalizedItemKeys()
        return version.map {
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
        incrementVersion()
    }

    suspend fun removeAliases(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ) {
        val editor = prefs().edit()
        itemKeys.normalizedItemKeys().forEach { itemKey ->
            editor.remove(
                aliasPrefsKey(
                    itemKey = itemKey,
                    languageTag = languageTag,
                    policyVersion = policyVersion
                )
            )
        }
        editor.apply()
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

            itemKey to overlay
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
        return readOverlayByKey(
            overlayKey = overlayKey,
            expectedCanonicalProvider = canonicalProvider,
            expectedCanonicalId = canonicalId,
            expectedContentType = contentType,
            expectedLanguageTag = languageTag,
            expectedPolicyVersion = policyVersion,
            nowMs = nowMs
        )
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
        version.value = version.value + 1
    }

    private fun overlayPrefsKey(overlayKey: String): String = "$OVERLAY_PREFIX${overlayKey.trim()}"

    private fun aliasPrefsKey(
        itemKey: String,
        languageTag: String,
        policyVersion: Int
    ): String = "$ALIAS_PREFIX${languageTag.trim()}::policy:$policyVersion::${itemKey.trim()}"

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
        if (displayHash != fields.hydratedHomeDisplayHash()) return false
        if (expectedCanonicalProvider != null && canonicalProvider != expectedCanonicalProvider) return false
        if (expectedCanonicalId != null && canonicalId.trim() != expectedCanonicalId.trim()) return false
        if (expectedContentType != null && contentType != expectedContentType) return false
        if (expectedLanguageTag != null && languageTag.trim() != expectedLanguageTag.trim()) return false
        if (expectedPolicyVersion != null && policyVersion != expectedPolicyVersion) return false

        return true
    }

    private companion object {
        const val TAG = "HydratedHomeOverlayStore"
        const val PREFS_NAME = "hydrated_home_overlay_v1"
        const val OVERLAY_PREFIX = "overlay::"
        const val ALIAS_PREFIX = "alias::"
        const val OVERLAY_SCHEMA_VERSION = 1
    }
}
