package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.repository.parseContentIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class WatchedSeriesEntry(
    val ids: Set<String>
)

internal const val GUEST_TRAKT_SESSION_KEY = "guest"

internal fun traktSessionKeyForState(state: TraktAuthState): String {
    return state.userSlug
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: state.username
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: GUEST_TRAKT_SESSION_KEY
}

@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val PREFS_NAME = "watched_series_state"
        private const val WATCHED_KEY_PREFIX = "entries_"
        private const val KNOWN_KEY_PREFIX = "known_entries_"
    }

    private val gson = Gson()
    private val _entries = MutableStateFlow<List<WatchedSeriesEntry>>(emptyList())
    val entries: StateFlow<List<WatchedSeriesEntry>> = _entries.asStateFlow()
    private val knownEntries = MutableStateFlow<List<WatchedSeriesEntry>>(emptyList())
    private val _activeSessionKey = MutableStateFlow(GUEST_TRAKT_SESSION_KEY)
    val activeSessionKey: StateFlow<String> = _activeSessionKey.asStateFlow()

    init {
        scope.launch {
            sessionKeys()
                .distinctUntilChanged()
                .collect { sessionKey ->
                    switchToSession(sessionKey)
                }
        }
    }

    suspend fun loadFromDisk() {
        switchToSession(currentSessionKey())
    }

    suspend fun setSeriesWatched(ids: Collection<String>, watched: Boolean) {
        ensureCurrentSessionLoaded()
        val normalizedIds = expandSeriesContentIdAliases(ids)
        if (normalizedIds.isEmpty()) return
        rememberSeriesIds(normalizedIds)
        val updated = if (watched) {
            mergeWatchedSeriesEntries(_entries.value + WatchedSeriesEntry(normalizedIds))
        } else {
            _entries.value.filterNot { entry ->
                entry.ids.any { it in normalizedIds }
            }
        }
        persist(updated)
    }

    suspend fun rememberSeriesIds(ids: Collection<String>) {
        ensureCurrentSessionLoaded()
        val normalizedIds = expandSeriesContentIdAliases(ids)
        if (normalizedIds.isEmpty()) return
        val updated = mergeWatchedSeriesEntries(
            knownEntries.value + WatchedSeriesEntry(normalizedIds)
        )
        persistKnown(updated)
    }

    suspend fun applyResolvedStates(
        watchedEntries: Collection<Set<String>>,
        unwatchedEntries: Collection<Set<String>>
    ) {
        ensureCurrentSessionLoaded()
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
        val entry = knownEntries.value.firstOrNull { candidate ->
            candidate.ids.any { it in aliases }
        }
        return entry?.ids.orEmpty() + aliases
    }

    private suspend fun persist(entries: List<WatchedSeriesEntry>) {
        _entries.value = mergeWatchedSeriesEntries(entries)
        persistKnown(mergeWatchedSeriesEntries(knownEntries.value + _entries.value))
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(
                prefKeyForActiveSession(prefix = WATCHED_KEY_PREFIX),
                gson.toJson(_entries.value)
            )
            .apply()
    }

    private suspend fun persistKnown(entries: List<WatchedSeriesEntry>) {
        knownEntries.value = mergeWatchedSeriesEntries(entries)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(
                prefKeyForActiveSession(prefix = KNOWN_KEY_PREFIX),
                gson.toJson(knownEntries.value)
            )
            .apply()
    }

    private fun decodeEntries(raw: String): List<WatchedSeriesEntry> {
        if (raw.isBlank()) return emptyList()
        val type = object : TypeToken<List<WatchedSeriesEntry>>() {}.type
        return runCatching {
            gson.fromJson<List<WatchedSeriesEntry>>(raw, type).orEmpty()
        }.getOrDefault(emptyList())
            .let(::mergeWatchedSeriesEntries)
    }

    private suspend fun ensureCurrentSessionLoaded() {
        val sessionKey = currentSessionKey()
        if (_activeSessionKey.value != sessionKey) {
            switchToSession(sessionKey)
        }
    }

    private suspend fun currentSessionKey(): String {
        return sessionKeys().first()
    }

    private fun sessionKeys(): Flow<String> {
        return traktAuthDataStore.state.map { state ->
            sessionIdForState(state)
        }
    }

    private fun sessionIdForState(state: TraktAuthState): String {
        return traktSessionKeyForState(state)
    }

    private fun switchToSession(sessionKey: String) {
        if (_activeSessionKey.value != sessionKey) {
            _entries.value = emptyList()
            knownEntries.value = emptyList()
            _activeSessionKey.value = sessionKey
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val restoredWatched = decodeEntries(
            prefs.getString(prefKeyForActiveSession(prefix = WATCHED_KEY_PREFIX), null).orEmpty()
        )
        val restoredKnown = decodeEntries(
            prefs.getString(prefKeyForActiveSession(prefix = KNOWN_KEY_PREFIX), null).orEmpty()
        )
        _entries.value = restoredWatched
        knownEntries.value = mergeWatchedSeriesEntries(restoredKnown + restoredWatched)
    }

    private fun prefKeyForActiveSession(prefix: String): String {
        return prefix + _activeSessionKey.value.lowercase()
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
