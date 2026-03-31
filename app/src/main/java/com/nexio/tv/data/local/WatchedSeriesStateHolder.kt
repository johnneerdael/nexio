package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.repository.parseContentIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class WatchedSeriesEntry(
    val ids: Set<String>
)

@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val traktAuthDataStore: TraktAuthDataStore
) {
    companion object {
        private const val PREFS_NAME = "watched_series_state"
        private const val KEY_PREFIX = "entries_"
    }

    private val gson = Gson()
    private val _entries = MutableStateFlow<List<WatchedSeriesEntry>>(emptyList())
    val entries: StateFlow<List<WatchedSeriesEntry>> = _entries.asStateFlow()

    suspend fun loadFromDisk() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(prefKeyForCurrentSession(), null).orEmpty()
        if (raw.isBlank()) {
            _entries.value = emptyList()
            return
        }
        val type = object : TypeToken<List<WatchedSeriesEntry>>() {}.type
        val restored = runCatching {
            gson.fromJson<List<WatchedSeriesEntry>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
        _entries.value = mergeWatchedSeriesEntries(restored)
    }

    suspend fun setSeriesWatched(ids: Collection<String>, watched: Boolean) {
        val normalizedIds = expandSeriesContentIdAliases(ids)
        if (normalizedIds.isEmpty()) return
        val updated = if (watched) {
            mergeWatchedSeriesEntries(_entries.value + WatchedSeriesEntry(normalizedIds))
        } else {
            _entries.value.filterNot { entry ->
                entry.ids.any { it in normalizedIds }
            }
        }
        persist(updated)
    }

    suspend fun applyResolvedStates(
        watchedEntries: Collection<Set<String>>,
        unwatchedEntries: Collection<Set<String>>
    ) {
        var updated = _entries.value
        if (unwatchedEntries.isNotEmpty()) {
            val unwatchedIds = unwatchedEntries.flatMapTo(linkedSetOf()) { expandSeriesContentIdAliases(it) }
            updated = updated.filterNot { entry ->
                entry.ids.any { it in unwatchedIds }
            }
        }
        if (watchedEntries.isNotEmpty()) {
            updated = mergeWatchedSeriesEntries(
                updated + watchedEntries.mapNotNull { ids ->
                    val normalized = expandSeriesContentIdAliases(ids)
                    normalized.takeIf { it.isNotEmpty() }?.let(::WatchedSeriesEntry)
                }
            )
        }
        persist(updated)
    }

    fun isSeriesWatched(contentId: String): Boolean {
        val aliases = expandSeriesContentIdAliases(listOf(contentId))
        if (aliases.isEmpty()) return false
        return _entries.value.any { entry ->
            entry.ids.any { it in aliases }
        }
    }

    fun matchingEntryIds(contentId: String): Set<String> {
        val aliases = expandSeriesContentIdAliases(listOf(contentId))
        if (aliases.isEmpty()) return emptySet()
        val entry = _entries.value.firstOrNull { candidate ->
            candidate.ids.any { it in aliases }
        }
        return entry?.ids.orEmpty() + aliases
    }

    private suspend fun persist(entries: List<WatchedSeriesEntry>) {
        _entries.value = mergeWatchedSeriesEntries(entries)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(
                prefKeyForCurrentSession(),
                gson.toJson(_entries.value)
            )
            .apply()
    }

    private suspend fun prefKeyForCurrentSession(): String {
        val state = traktAuthDataStore.state.first()
        val sessionId = state.userSlug
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: state.username
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: "guest"
        return KEY_PREFIX + sessionId.lowercase()
    }
}

internal fun expandSeriesContentIdAliases(ids: Collection<String>): Set<String> {
    return buildSet {
        ids.forEach { rawId ->
            val normalized = rawId.trim()
            if (normalized.isBlank()) return@forEach
            add(normalized)
            val parsed = parseContentIds(normalized)
            parsed.imdb?.takeIf { it.isNotBlank() }?.let { add(it) }
            parsed.tmdb?.let { add("tmdb:$it") }
            parsed.trakt?.let { add("trakt:$it") }
        }
    }
}

internal fun mergeWatchedSeriesEntries(
    entries: Collection<WatchedSeriesEntry>
): List<WatchedSeriesEntry> {
    val merged = mutableListOf<MutableSet<String>>()
    entries.forEach { entry ->
        val normalized = expandSeriesContentIdAliases(entry.ids)
        if (normalized.isEmpty()) return@forEach
        val overlapping = merged.filter { candidate -> candidate.any { it in normalized } }
        if (overlapping.isEmpty()) {
            merged += normalized.toMutableSet()
        } else {
            val combined = normalized.toMutableSet()
            overlapping.forEach { combined += it }
            merged.removeAll(overlapping.toSet())
            merged += combined
        }
    }
    return merged
        .map { ids -> WatchedSeriesEntry(ids = ids.toSet()) }
        .sortedBy { it.ids.minOrNull().orEmpty() }
}
