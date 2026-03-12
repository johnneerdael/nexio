package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ContinueWatchingSnapshot(
    val movieProgressItems: List<WatchProgress> = emptyList(),
    val nextUpItems: List<TraktProgressService.CalendarShowEntry> = emptyList(),
    val updatedAtMs: Long = 0L
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingSnapshotService @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val traktProgressService: TraktProgressService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val snapshotStore: ContinueWatchingSnapshotStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawSnapshotState = MutableStateFlow(ContinueWatchingSnapshot())
    private val snapshotState = MutableStateFlow(ContinueWatchingSnapshot())
    private val refreshMutex = Mutex()
    private var lastRefreshRequestMs = 0L
    private val minRefreshIntervalMs = 30_000L
    @Volatile
    private var hasSeenAuthenticatedSession = false

    init {
        scope.launch {
            snapshotStore.read()?.let { persisted ->
                val normalized = sanitizeSnapshot(persisted)
                rawSnapshotState.value = normalized
                snapshotState.value = normalized
                lastRefreshRequestMs = normalized.updatedAtMs
            }
        }

        scope.launch {
            combine(
                rawSnapshotState,
                traktSettingsDataStore.dismissedNextUpKeys
            ) { snapshot, dismissedKeys ->
                if (dismissedKeys.isEmpty()) {
                    snapshot
                } else {
                    snapshot.copy(
                        nextUpItems = snapshot.nextUpItems.filter { entry ->
                            entry.contentId.trim() !in dismissedKeys
                        }
                    )
                }
            }.collectLatest { filtered ->
                if (filtered != snapshotState.value) {
                    snapshotState.value = filtered
                }
            }
        }

        scope.launch {
            traktAuthDataStore.isEffectivelyAuthenticated
                .distinctUntilChanged()
                .flatMapLatest { isAuthenticated ->
                    if (!isAuthenticated) {
                        if (hasSeenAuthenticatedSession) {
                            rawSnapshotState.value = ContinueWatchingSnapshot()
                            snapshotStore.clear()
                            lastRefreshRequestMs = 0L
                        }
                        hasSeenAuthenticatedSession = false
                        flowOf<ContinueWatchingSnapshot?>(null)
                    } else {
                        hasSeenAuthenticatedSession = true
                        combine(
                            traktProgressService.observeRemoteSnapshotLoaded(),
                            watchProgressRepository.allProgress,
                            traktProgressService.observeMyShowsCalendar()
                        ) { hasLoadedRemoteSnapshot, allProgress, calendarEntries ->
                            if (!hasLoadedRemoteSnapshot) {
                                null
                            } else {
                                buildRawSnapshot(
                                    allProgress = allProgress,
                                    calendarEntries = calendarEntries
                                )
                            }
                        }
                    }
                }
                .collectLatest { snapshot ->
                    if (snapshot == null) return@collectLatest
                    updateSnapshot(snapshot)
                }
        }
    }

    fun observeSnapshot(): Flow<ContinueWatchingSnapshot> {
        return snapshotState.onStart {
            scope.launch {
                runCatching { ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w("ContinueWatching", "Failed to refresh continue watching snapshot", error)
                    }
            }
        }
    }

    suspend fun ensureFresh(force: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshRequestMs < minRefreshIntervalMs && snapshotState.value.updatedAtMs > 0L) {
            return@withContext
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force &&
                lockedNow - lastRefreshRequestMs < minRefreshIntervalMs &&
                snapshotState.value.updatedAtMs > 0L
            ) {
                return@withLock
            }
            traktProgressService.refreshNow()
            lastRefreshRequestMs = lockedNow
        }
    }

    fun removeShowOptimistically(contentId: String) {
        val target = contentId.trim()
        if (target.isBlank()) return
        val updated = rawSnapshotState.value.copy(
            nextUpItems = rawSnapshotState.value.nextUpItems.filterNot { it.contentId == target },
            updatedAtMs = System.currentTimeMillis()
        )
        persistRawSnapshot(updated)
    }

    private fun buildRawSnapshot(
        allProgress: List<WatchProgress>,
        calendarEntries: List<TraktProgressService.CalendarShowEntry>
    ): ContinueWatchingSnapshot {
        val movieItems = allProgress
            .asSequence()
            .filter { it.contentType.equals("movie", ignoreCase = true) }
            .filter { shouldTreatAsMovieResumeForContinueWatching(it) }
            .filter { it.contentId.isNotBlank() && it.videoId.isNotBlank() }
            .sortedByDescending { it.lastWatched }
            .toList()
        val nextUpItems = calendarEntries
            .asSequence()
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.firstAiredMs }
            .distinctBy { it.contentId }
            .toList()

        return ContinueWatchingSnapshot(
            movieProgressItems = movieItems,
            nextUpItems = nextUpItems,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun shouldTreatAsMovieResumeForContinueWatching(progress: WatchProgress): Boolean {
        if (!progress.contentType.equals("movie", ignoreCase = true)) return false
        if (progress.isInProgress()) return true
        if (progress.isCompleted()) return false
        return progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
    }

    private fun sanitizeSnapshot(snapshot: ContinueWatchingSnapshot): ContinueWatchingSnapshot {
        val movieItems = snapshot.movieProgressItems.filter { progress ->
            runCatching {
                progress.contentId.isNotBlank() &&
                    progress.videoId.isNotBlank() &&
                    shouldTreatAsMovieResumeForContinueWatching(progress)
            }.getOrDefault(false)
        }
        val nextUpItems = snapshot.nextUpItems
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.firstAiredMs }
            .distinctBy { it.contentId }
        val updatedAtMs = if (snapshot.updatedAtMs > 0L) snapshot.updatedAtMs else System.currentTimeMillis()
        return ContinueWatchingSnapshot(
            movieProgressItems = movieItems,
            nextUpItems = nextUpItems,
            updatedAtMs = updatedAtMs
        )
    }

    private fun normalizeNextUpEntry(
        entry: TraktProgressService.CalendarShowEntry
    ): TraktProgressService.CalendarShowEntry? {
        return try {
            val contentId = entry.contentId.trim()
            if (contentId.isBlank()) return null
            val season = entry.season.takeIf { it > 0 } ?: return null
            val episode = entry.episode.takeIf { it > 0 } ?: return null
            entry.copy(
                contentId = contentId,
                contentType = entry.contentType.takeIf { it.isNotBlank() } ?: "series",
                name = entry.name.takeIf { it.isNotBlank() } ?: contentId,
                videoId = entry.videoId.takeIf { it.isNotBlank() } ?: "$contentId:$season:$episode"
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun persistRawSnapshot(snapshot: ContinueWatchingSnapshot) {
        val normalized = sanitizeSnapshot(snapshot)
        rawSnapshotState.value = normalized
        snapshotStore.write(normalized)
        lastRefreshRequestMs = normalized.updatedAtMs
    }

    private fun updateSnapshot(snapshot: ContinueWatchingSnapshot) {
        persistRawSnapshot(snapshot)
    }
}
