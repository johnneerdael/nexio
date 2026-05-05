package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile authoritative store for Modern Home rail order.
 *
 * `state` is sourced from DataStore via `stateIn` and is eventually-consistent.
 * Mutations route through a mutex-guarded path that uses `lastWrittenState` as
 * the in-memory authoritative copy: the first mutation seeds the cache by
 * awaiting `homeRailOrderStateJson.first()` (so the persisted state is observed
 * before any merge logic runs), and subsequent mutations read directly from the
 * cache. This handles two race classes:
 *  - Initial-load race: `state.value` may still be the initial Empty when the
 *    first mutation runs, before the `stateIn` upstream has decoded its first
 *    emission. Seeding from the flow avoids overwriting persisted state.
 *  - Back-to-back race: DataStore writes round-trip asynchronously, so
 *    `state.value` between two locked mutations may not yet reflect the prior
 *    write. The cache holds the just-written value.
 *
 * Callers that invoke `updateOrder(...)` without an explicit `knownLiveKeys`
 * rely on the `knownLiveKeysCache` populated by `effectiveOrder(...)`'s
 * `combine` transform. Production callers (the home pipeline) hold a long-lived
 * `effectiveOrder` collection before any user-initiated reorder, so the cache
 * is always populated. Tests and any caller that mutates without first
 * subscribing should pass `knownLiveKeys` explicitly.
 */
@Singleton
class HomeRailOrderStore @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val codec: HomeRailOrderStateCodec,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val reconciler: HomeRailOrderReconciler = HomeRailOrderReconciler(),
) {
    private val mutationLock = Mutex()
    private val knownLiveKeysCache = MutableStateFlow<Set<HomeRailKey>>(emptySet())
    private var lastWrittenState: HomeRailOrderState? = null

    val state: StateFlow<HomeRailOrderState> = layoutPreferenceDataStore.homeRailOrderStateJson
        .map { codec.decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, HomeRailOrderState.Empty)

    fun effectiveOrder(
        liveDefinitions: Flow<List<HomeRailDefinition>>,
    ): StateFlow<EffectiveHomeRailOrder> =
        combine(state, liveDefinitions) { s, defs ->
            knownLiveKeysCache.value = defs.map { it.key }.toSet()
            reconciler.reconcile(s.orderedKeys, s.disabledKeys, defs)
        }.stateIn(scope, SharingStarted.Eagerly, EffectiveHomeRailOrder.Empty)

    suspend fun updateOrder(
        orderedKeys: List<HomeRailKey>,
        source: RailOrderMutationSource,
        knownLiveKeys: Set<HomeRailKey> = knownLiveKeysCache.value,
    ) = mutationLock.withLock {
        val current = currentForMutation()
        val unknownInCurrent = current.orderedKeys.filter {
            it !in knownLiveKeys && it !in orderedKeys
        }
        val merged = orderedKeys + unknownInCurrent
        persist(current.copy(
            orderedKeys = merged,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
    }

    suspend fun setEnabled(
        key: HomeRailKey,
        enabled: Boolean,
        source: RailOrderMutationSource,
    ) = mutationLock.withLock {
        val current = currentForMutation()
        val newDisabled = if (enabled) current.disabledKeys - key else current.disabledKeys + key
        if (newDisabled == current.disabledKeys) return@withLock
        persist(current.copy(
            disabledKeys = newDisabled,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
    }

    suspend fun reorderProviderKeys(
        family: RailFamily,
        providerOrder: List<HomeRailKey>,
        source: RailOrderMutationSource,
        liveDefinitions: List<HomeRailDefinition>,
    ) = mutationLock.withLock {
        val current = currentForMutation()
        val merged = spliceProviderKeys(
            current = current.orderedKeys,
            family = family,
            providerOrder = providerOrder,
            liveDefinitions = liveDefinitions,
        )
        if (merged == current.orderedKeys) return@withLock
        persist(current.copy(
            orderedKeys = merged,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
    }

    private suspend fun currentForMutation(): HomeRailOrderState {
        lastWrittenState?.let { return it }
        val initial = codec.decode(layoutPreferenceDataStore.homeRailOrderStateJson.first())
        lastWrittenState = initial
        return initial
    }

    private suspend fun persist(state: HomeRailOrderState) {
        layoutPreferenceDataStore.setHomeRailOrderStateJson(codec.encode(state))
        lastWrittenState = state
    }
}
