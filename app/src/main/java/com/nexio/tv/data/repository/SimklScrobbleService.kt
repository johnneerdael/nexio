package com.nexio.tv.data.repository

import com.nexio.tv.data.repository.simkl.SimklScrobbleMutationAdapter
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

internal sealed interface SimklScrobbleMutationResult {
    data object Success : SimklScrobbleMutationResult
    data object Failed : SimklScrobbleMutationResult
    data object Collapsed : SimklScrobbleMutationResult
}

@Singleton
class SimklScrobbleService @Inject constructor(
    private val trackingProviderStateService: TrackingProviderStateService,
    private val watchingNowStateController: TraktWatchingNowStateController,
    private val traktMutationOutboxCoordinator: ProviderMutationOutboxCoordinator,
    private val profileManager: com.nexio.tv.core.profile.ProfileManager,
    private val traceMetadataEvents: com.nexio.tv.core.trace.TraceMetadataEvents
) {
    data class WatchingNowState(
        val active: Boolean = false,
        val title: String? = null,
        val contentType: String? = null,
        val progressPercent: Float? = null,
        val updatedAtMs: Long = 0L
    )

    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long
    )

    private data class ProfileScrobbleState(
        var lastScrobbleStamp: ScrobbleStamp? = null,
        var pendingMutation: QueuedSimklWatchingMutation? = null,
        var pendingMutationDrainJob: Job? = null
    )

    private val watchingNowState = watchingNowStateController.observe()
        .map { snapshot ->
            WatchingNowState(
                active = snapshot.active,
                title = snapshot.title,
                contentType = snapshot.contentType,
                progressPercent = snapshot.progressPercent,
                updatedAtMs = snapshot.updatedAtMs
            )
        }
        .stateIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            started = SharingStarted.Eagerly,
            initialValue = WatchingNowState()
        )

    private val mutationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingMutationMutex = Mutex()
    private val profileScrobbleStates = mutableMapOf<Int, ProfileScrobbleState>()
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f

    suspend fun scrobbleStart(item: TrackingScrobbleItem, progressPercent: Float, ownerProfileId: Int? = null) {
        val profileId = ownerProfileId ?: trackingProviderStateService.currentProfileId()
        if (!canMutateWatchingState(profileId)) return
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        submitMutation(
            request = SimklWatchingMutationRequest.Scrobble(
                action = "start",
                item = item,
                progressPercent = progressPercent,
                optimisticVersion = optimisticVersion,
                profileId = profileId
            )
        )
    }

    suspend fun scrobbleStop(item: TrackingScrobbleItem, progressPercent: Float, ownerProfileId: Int? = null) {
        val profileId = ownerProfileId ?: trackingProviderStateService.currentProfileId()
        if (!canMutateWatchingState(profileId)) return
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        submitMutation(
            request = SimklWatchingMutationRequest.Scrobble(
                action = "stop",
                item = item,
                progressPercent = progressPercent,
                optimisticVersion = optimisticVersion,
                profileId = profileId
            )
        )
    }

    suspend fun scrobblePause(item: TrackingScrobbleItem, progressPercent: Float, ownerProfileId: Int? = null) {
        val profileId = ownerProfileId ?: trackingProviderStateService.currentProfileId()
        if (!canMutateWatchingState(profileId)) return
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        submitMutation(
            request = SimklWatchingMutationRequest.Scrobble(
                action = "pause",
                item = item,
                progressPercent = progressPercent,
                optimisticVersion = optimisticVersion,
                profileId = profileId
            )
        )
    }

    suspend fun checkin(item: TrackingScrobbleItem, message: String? = null, ownerProfileId: Int? = null): Boolean {
        val profileId = ownerProfileId ?: trackingProviderStateService.currentProfileId()
        if (!canMutateWatchingState(profileId)) return false
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        return submitMutation(
            request = SimklWatchingMutationRequest.CheckIn(
                item = item,
                message = message,
                optimisticVersion = optimisticVersion,
                profileId = profileId
            )
        ) == SimklScrobbleMutationResult.Success
    }

    fun observeWatchingNowState(): StateFlow<WatchingNowState> = watchingNowState

    private suspend fun canMutateWatchingState(profileId: Int): Boolean =
        trackingProviderStateService.currentState(profileId).simklAuthenticated

    private suspend fun submitMutation(request: SimklWatchingMutationRequest): SimklScrobbleMutationResult {
        val result = CompletableDeferred<SimklScrobbleMutationResult>()
        pendingMutationMutex.withLock {
            val state = stateFor(request.profileId)
            val incoming = QueuedSimklWatchingMutation(
                mutation = PendingSimklWatchingMutation(
                    request = request,
                    rollbackState = watchingNowStateController.current(),
                    optimisticVersion = request.optimisticVersion
                ),
                result = result
            )
            watchingNowStateController.publish(request.toWatchingNowState())
            val existing = state.pendingMutation
            existing?.result?.complete(SimklScrobbleMutationResult.Collapsed)
            state.pendingMutation = replacePendingSimklWatchingMutation(existing?.mutation, incoming.mutation)
                .let { merged -> incoming.copy(mutation = merged) }
            if (state.pendingMutationDrainJob?.isActive != true) {
                state.pendingMutationDrainJob = mutationScope.launch { drainPendingMutations(request.profileId) }
            }
        }
        return result.await()
    }

    private suspend fun drainPendingMutations(profileId: Int) {
        while (true) {
            val next = pendingMutationMutex.withLock {
                val state = stateFor(profileId)
                val queued = state.pendingMutation ?: run {
                    state.pendingMutationDrainJob = null
                    return
                }
                state.pendingMutation = null
                queued
            }
            val result = executeMutation(next.mutation)
            if (result == SimklScrobbleMutationResult.Failed) {
                watchingNowStateController.rollbackIfCurrent(
                    expectedVersion = next.mutation.optimisticVersion,
                    rollbackState = next.mutation.rollbackState
                )
            }
            next.result.complete(result)
        }
    }

    private suspend fun executeMutation(mutation: PendingSimklWatchingMutation): SimklScrobbleMutationResult {
        return when (val request = mutation.request) {
            is SimklWatchingMutationRequest.CheckIn -> enqueueCheckin(request, mutation.rollbackState)
            is SimklWatchingMutationRequest.Scrobble -> enqueueScrobble(request, mutation.rollbackState)
        }
    }

    private suspend fun enqueueCheckin(
        request: SimklWatchingMutationRequest.CheckIn,
        rollbackState: TraktWatchingNowStateController.Snapshot
    ): SimklScrobbleMutationResult {
        if (!checkScrobbleBoundary(request.profileId, "checkin")) return SimklScrobbleMutationResult.Failed
        return runCatching {
            traktMutationOutboxCoordinator.enqueueAndDrain(
                SimklScrobbleMutationAdapter.buildCheckinEnvelope(
                    item = request.item,
                    message = request.message,
                    rollbackState = rollbackState,
                    optimisticVersion = request.optimisticVersion,
                    profileId = request.profileId
                )
            )
            SimklScrobbleMutationResult.Success
        }.getOrElse { SimklScrobbleMutationResult.Failed }
    }

    private suspend fun enqueueScrobble(
        request: SimklWatchingMutationRequest.Scrobble,
        rollbackState: TraktWatchingNowStateController.Snapshot
    ): SimklScrobbleMutationResult {
        val action = request.action
        val item = request.item
        val clampedProgress = request.progressPercent.coerceIn(0f, 100f)
        if (!checkScrobbleBoundary(request.profileId, "scrobble.$action")) return SimklScrobbleMutationResult.Failed
        if (shouldSkip(request.profileId, action, item.itemKey(), clampedProgress)) return SimklScrobbleMutationResult.Success
        return runCatching {
            traktMutationOutboxCoordinator.enqueueAndDrain(
                SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
                    item = item,
                    action = action,
                    progressPercent = clampedProgress,
                    rollbackState = rollbackState,
                    optimisticVersion = request.optimisticVersion,
                    profileId = request.profileId
                )
            )
            stateFor(request.profileId).lastScrobbleStamp = ScrobbleStamp(
                action = action,
                itemKey = item.itemKey(),
                progress = clampedProgress,
                timestampMs = System.currentTimeMillis()
            )
            SimklScrobbleMutationResult.Success
        }.getOrElse { SimklScrobbleMutationResult.Failed }
    }

    private fun stateFor(profileId: Int): ProfileScrobbleState =
        profileScrobbleStates.getOrPut(profileId) { ProfileScrobbleState() }

    private fun checkScrobbleBoundary(envelopeProfileId: Int, operation: String): Boolean {
        val active = profileManager.activeProfileId.value
        if (envelopeProfileId != active) {
            traceMetadataEvents.emitScrobbleRejected(
                envelopeProfileId = envelopeProfileId,
                activeProfileId = active,
                operation = "simkl.$operation",
                reason = "STALE_SESSION_WRITE_REJECTED"
            )
            return false
        }
        return true
    }

    private fun shouldSkip(profileId: Int, action: String, itemKey: String, progress: Float): Boolean {
        val last = stateFor(profileId).lastScrobbleStamp ?: return false
        val now = System.currentTimeMillis()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow
        return isSameWindow && isSameAction && isSameItem && isNearProgress
    }
}

private sealed interface SimklWatchingMutationRequest {
    val optimisticVersion: Long
    val profileId: Int
    fun toWatchingNowState(): TraktWatchingNowStateController.Snapshot

    data class Scrobble(
        val action: String,
        val item: TrackingScrobbleItem,
        val progressPercent: Float,
        override val optimisticVersion: Long,
        override val profileId: Int
    ) : SimklWatchingMutationRequest {
        override fun toWatchingNowState(): TraktWatchingNowStateController.Snapshot {
            val progress = progressPercent.coerceIn(0f, 100f)
            return TraktWatchingNowStateController.Snapshot(
                active = action == "start",
                title = item.displayTitle(),
                contentType = item.contentType(),
                progressPercent = progress
            )
        }
    }

    data class CheckIn(
        val item: TrackingScrobbleItem,
        val message: String?,
        override val optimisticVersion: Long,
        override val profileId: Int
    ) : SimklWatchingMutationRequest {
        override fun toWatchingNowState(): TraktWatchingNowStateController.Snapshot {
            return TraktWatchingNowStateController.Snapshot(
                active = true,
                title = item.displayTitle(),
                contentType = item.contentType(),
                progressPercent = null
            )
        }
    }
}

private data class PendingSimklWatchingMutation(
    val request: SimklWatchingMutationRequest,
    val rollbackState: TraktWatchingNowStateController.Snapshot,
    val optimisticVersion: Long
)

private data class QueuedSimklWatchingMutation(
    val mutation: PendingSimklWatchingMutation,
    val result: CompletableDeferred<SimklScrobbleMutationResult>
)

private fun replacePendingSimklWatchingMutation(
    existing: PendingSimklWatchingMutation?,
    incoming: PendingSimklWatchingMutation
): PendingSimklWatchingMutation = incoming

private fun TrackingScrobbleItem.itemKey(): String = when (this) {
    is TrackingScrobbleItem.Movie -> "movie:$contentId:${year ?: 0}"
    is TrackingScrobbleItem.Episode -> "episode:$contentId:$season:$number"
}

private fun TrackingScrobbleItem.displayTitle(): String? = when (this) {
    is TrackingScrobbleItem.Movie -> title?.takeIf { it.isNotBlank() }
    is TrackingScrobbleItem.Episode -> buildString {
        append(showTitle.orEmpty())
        append(" S")
        append(season)
        append("E")
        append(number)
        if (!episodeTitle.isNullOrBlank()) {
            append(" ")
            append(episodeTitle)
        }
    }.trim().takeIf { it.isNotBlank() }
}

private fun TrackingScrobbleItem.contentType(): String = when (this) {
    is TrackingScrobbleItem.Movie -> "movie"
    is TrackingScrobbleItem.Episode -> "episode"
}
