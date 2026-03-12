package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.androidTvRecommendationsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "android_tv_recommendations"
)

data class AndroidTvRecommendationsPreferences(
    val enabled: Boolean = false,
    val selectedFeedKeys: List<String> = emptyList(),
    val pendingBrowsableChannelIds: List<Long> = emptyList(),
    val requestedBrowsableChannelIds: List<Long> = emptyList(),
    val browsableRequestCooldownUntilMsByChannelId: Map<Long, Long> = emptyMap()
)

@Singleton
class AndroidTvRecommendationsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.androidTvRecommendationsDataStore
    private val gson = Gson()

    private val enabledKey = booleanPreferencesKey("android_tv_recommendations_enabled")
    private val selectedFeedKeysKey = stringPreferencesKey("android_tv_recommendations_selected_feed_keys")
    private val pendingBrowsableChannelIdsKey = stringPreferencesKey("android_tv_pending_browsable_channel_ids")
    private val requestedBrowsableChannelIdsKey = stringPreferencesKey("android_tv_requested_browsable_channel_ids")
    private val browsableRequestCooldownKey = stringPreferencesKey("android_tv_browsable_request_cooldowns")

    val preferences: Flow<AndroidTvRecommendationsPreferences> = dataStore.data.map { prefs ->
        val cooldowns = parseCooldowns(prefs[browsableRequestCooldownKey])
        AndroidTvRecommendationsPreferences(
            enabled = prefs[enabledKey] ?: false,
            selectedFeedKeys = parseKeys(prefs[selectedFeedKeysKey]),
            pendingBrowsableChannelIds = parseLongs(prefs[pendingBrowsableChannelIdsKey]),
            requestedBrowsableChannelIds = parseLongs(prefs[requestedBrowsableChannelIdsKey]),
            browsableRequestCooldownUntilMsByChannelId = cooldowns
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[enabledKey] = enabled
        }
    }

    suspend fun setSelectedFeedKeys(keys: List<String>) {
        val normalized = normalizeKeys(keys)
        dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(selectedFeedKeysKey)
            } else {
                prefs[selectedFeedKeysKey] = gson.toJson(normalized)
            }
        }
    }

    suspend fun toggleSelectedFeedKey(key: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return
        dataStore.edit { prefs ->
            val current = parseKeys(prefs[selectedFeedKeysKey]).toMutableList()
            if (normalizedKey in current) {
                current.remove(normalizedKey)
            } else {
                current.add(normalizedKey)
            }
            val next = normalizeKeys(current)
            if (next.isEmpty()) {
                prefs.remove(selectedFeedKeysKey)
            } else {
                prefs[selectedFeedKeysKey] = gson.toJson(next)
            }
        }
    }

    suspend fun enqueueBrowsableChannelId(channelId: Long) {
        if (channelId <= 0L) return
        dataStore.edit { prefs ->
            val nowMs = System.currentTimeMillis()
            val cooldowns = pruneExpiredCooldowns(parseCooldowns(prefs[browsableRequestCooldownKey]), nowMs)
            val requested = parseLongs(prefs[requestedBrowsableChannelIdsKey])
            if (channelId in requested) return@edit
            if ((cooldowns[channelId] ?: 0L) > nowMs) {
                prefs[browsableRequestCooldownKey] = encodeCooldowns(cooldowns)
                return@edit
            }

            val pending = parseLongs(prefs[pendingBrowsableChannelIdsKey]).toMutableList()
            if (channelId !in pending) {
                pending += channelId
                prefs[pendingBrowsableChannelIdsKey] = gson.toJson(normalizeLongs(pending))
            }
            prefs[browsableRequestCooldownKey] = encodeCooldowns(cooldowns)
        }
    }

    suspend fun markBrowsableChannelRequested(channelId: Long) {
        if (channelId <= 0L) return
        dataStore.edit { prefs ->
            val nowMs = System.currentTimeMillis()
            val cooldowns = pruneExpiredCooldowns(parseCooldowns(prefs[browsableRequestCooldownKey]), nowMs)
            val pending = parseLongs(prefs[pendingBrowsableChannelIdsKey]).filterNot { it == channelId }
            val requested = parseLongs(prefs[requestedBrowsableChannelIdsKey]).toMutableList()
            if (channelId !in requested) {
                requested += channelId
            }

            if (pending.isEmpty()) {
                prefs.remove(pendingBrowsableChannelIdsKey)
            } else {
                prefs[pendingBrowsableChannelIdsKey] = gson.toJson(normalizeLongs(pending))
            }

            prefs[requestedBrowsableChannelIdsKey] = gson.toJson(normalizeLongs(requested))
            val nextCooldowns = cooldowns.toMutableMap().apply { remove(channelId) }
            if (nextCooldowns.isEmpty()) {
                prefs.remove(browsableRequestCooldownKey)
            } else {
                prefs[browsableRequestCooldownKey] = encodeCooldowns(nextCooldowns)
            }
        }
    }

    suspend fun markBrowsableChannelCooldown(channelId: Long, cooldownDurationMs: Long) {
        if (channelId <= 0L || cooldownDurationMs <= 0L) return
        dataStore.edit { prefs ->
            val nowMs = System.currentTimeMillis()
            val pending = parseLongs(prefs[pendingBrowsableChannelIdsKey]).filterNot { it == channelId }
            val cooldowns = pruneExpiredCooldowns(parseCooldowns(prefs[browsableRequestCooldownKey]), nowMs).toMutableMap()
            cooldowns[channelId] = nowMs + cooldownDurationMs

            if (pending.isEmpty()) {
                prefs.remove(pendingBrowsableChannelIdsKey)
            } else {
                prefs[pendingBrowsableChannelIdsKey] = gson.toJson(normalizeLongs(pending))
            }
            prefs[browsableRequestCooldownKey] = encodeCooldowns(cooldowns)
        }
    }

    suspend fun clearBrowsableChannelRequest(channelId: Long) {
        if (channelId <= 0L) return
        dataStore.edit { prefs ->
            val nowMs = System.currentTimeMillis()
            val pending = parseLongs(prefs[pendingBrowsableChannelIdsKey]).filterNot { it == channelId }
            val requested = parseLongs(prefs[requestedBrowsableChannelIdsKey]).filterNot { it == channelId }
            val cooldowns = pruneExpiredCooldowns(parseCooldowns(prefs[browsableRequestCooldownKey]), nowMs).toMutableMap()
            cooldowns.remove(channelId)

            if (pending.isEmpty()) {
                prefs.remove(pendingBrowsableChannelIdsKey)
            } else {
                prefs[pendingBrowsableChannelIdsKey] = gson.toJson(normalizeLongs(pending))
            }

            if (requested.isEmpty()) {
                prefs.remove(requestedBrowsableChannelIdsKey)
            } else {
                prefs[requestedBrowsableChannelIdsKey] = gson.toJson(normalizeLongs(requested))
            }

            if (cooldowns.isEmpty()) {
                prefs.remove(browsableRequestCooldownKey)
            } else {
                prefs[browsableRequestCooldownKey] = encodeCooldowns(cooldowns)
            }
        }
    }

    private fun parseKeys(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            normalizeKeys(gson.fromJson<List<String>>(raw, type).orEmpty())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLongs(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<Long>>() {}.type
            normalizeLongs(gson.fromJson<List<Long>>(raw, type).orEmpty())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCooldowns(raw: String?): Map<Long, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            val parsed = gson.fromJson<Map<String, Long>>(raw, type).orEmpty()
            parsed.entries
                .mapNotNull { (key, value) ->
                    key.toLongOrNull()?.takeIf { it > 0L }?.let { channelId ->
                        channelId to value
                    }
                }
                .toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encodeCooldowns(cooldowns: Map<Long, Long>): String {
        val normalized = cooldowns.entries
            .asSequence()
            .filter { it.key > 0L && it.value > 0L }
            .associate { it.key.toString() to it.value }
        return gson.toJson(normalized)
    }

    private fun pruneExpiredCooldowns(cooldowns: Map<Long, Long>, nowMs: Long): Map<Long, Long> {
        return cooldowns.filterValues { it > nowMs }
    }

    private fun normalizeKeys(keys: List<String>): List<String> {
        return keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun normalizeLongs(values: List<Long>): List<Long> {
        return values.asSequence()
            .filter { it > 0L }
            .distinct()
            .toList()
    }
}
