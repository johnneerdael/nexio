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
    val requestedBrowsableChannelIds: List<Long> = emptyList()
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

    val preferences: Flow<AndroidTvRecommendationsPreferences> = dataStore.data.map { prefs ->
        AndroidTvRecommendationsPreferences(
            enabled = prefs[enabledKey] ?: false,
            selectedFeedKeys = parseKeys(prefs[selectedFeedKeysKey]),
            pendingBrowsableChannelIds = parseLongs(prefs[pendingBrowsableChannelIdsKey]),
            requestedBrowsableChannelIds = parseLongs(prefs[requestedBrowsableChannelIdsKey])
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
            val pending = parseLongs(prefs[pendingBrowsableChannelIdsKey]).toMutableList()
            if (channelId !in pending) {
                pending += channelId
                prefs[pendingBrowsableChannelIdsKey] = gson.toJson(normalizeLongs(pending))
            }
        }
    }

    suspend fun markBrowsableChannelRequested(channelId: Long) {
        if (channelId <= 0L) return
        dataStore.edit { prefs ->
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
        }
    }

    suspend fun clearBrowsableChannelRequest(channelId: Long) {
        if (channelId <= 0L) return
        dataStore.edit { prefs ->
            val pending = parseLongs(prefs[pendingBrowsableChannelIdsKey]).filterNot { it == channelId }
            val requested = parseLongs(prefs[requestedBrowsableChannelIdsKey]).filterNot { it == channelId }

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
