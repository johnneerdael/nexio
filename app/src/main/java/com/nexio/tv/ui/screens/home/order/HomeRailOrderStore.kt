package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.core.di.ApplicationScope
import com.nexio.tv.core.profile.ProfileManager
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
import kotlinx.coroutines.launch
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
 * rely on the `knownLiveKeysCache` populated by the methods that receive
 * `liveDefinitions`. Production callers (the home pipeline) call `tryMigrate`,
 * `onLiveDefinitionsArrived`, and `reconcileNow` every tick; the cache is
 * populated by those. Tests that mutate the store without first calling one of
 * these should pass `knownLiveKeys` explicitly. `effectiveOrder(...)` also
 * populates the cache, so the contract is consistent across all
 * liveDefinitions-receiving methods.
 */
@Singleton
class HomeRailOrderStore @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val codec: HomeRailOrderStateCodec,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    private val diagnostics: HomeRailOrderDiagnosticsSink,
    private val profileManager: ProfileManager,
    private val reconciler: HomeRailOrderReconciler = HomeRailOrderReconciler(),
) {
    private val mutationLock = Mutex()
    private val knownLiveKeysCache = MutableStateFlow<Set<HomeRailKey>>(emptySet())
    private var lastWrittenState: HomeRailOrderState? = null
    private var lastKnownLiveDefinitions: List<HomeRailDefinition> = emptyList()

    init {
        scope.launch {
            var lastSeenProfileId: Int? = null
            profileManager.activeProfileId.collect { profileId ->
                if (lastSeenProfileId != null && lastSeenProfileId != profileId) {
                    mutationLock.withLock {
                        lastWrittenState = null
                        lastKnownLiveDefinitions = emptyList()
                    }
                }
                lastSeenProfileId = profileId
            }
        }
    }

    val state: StateFlow<HomeRailOrderState> = layoutPreferenceDataStore.homeRailOrderStateJson
        .map { codec.decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, HomeRailOrderState.Empty)

    fun effectiveOrder(
        liveDefinitions: Flow<List<HomeRailDefinition>>,
    ): StateFlow<EffectiveHomeRailOrder> =
        combine(state, liveDefinitions) { s, defs ->
            knownLiveKeysCache.value = defs.map { it.key }.toSet()
            val result = reconciler.reconcile(s.orderedKeys, s.disabledKeys, defs)
            emitReconciledDiagnostics(s, defs, result)
            result
        }.stateIn(scope, SharingStarted.Eagerly, EffectiveHomeRailOrder.Empty)

    private fun emitReconciledDiagnostics(
        current: HomeRailOrderState,
        liveDefinitions: List<HomeRailDefinition>,
        result: EffectiveHomeRailOrder,
    ) {
        diagnostics.emitReconciled(
            savedGlobalOrder = current.orderedKeys,
            providerOrders = liveDefinitions.groupBy({ it.family }, { it.key }),
            persistedSyntheticOrder = emptyList(),
            liveDefinitionOrder = liveDefinitions.map { it.key },
            effectiveOrder = result.visibleKeys,
            disabledKeys = result.disabledKeys,
            newlyDiscoveredKeys = result.newlyDiscoveredKeys,
            ignoredOrderSources = listOf("persistedSyntheticOrder"),
            mutationSource = current.lastMutationSource,
        )
        result.newlyDiscoveredKeys.forEach { diagnostics.emitAddedFromMissingDefault(it) }
    }

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
        val before = current.orderedKeys
        persist(current.copy(
            orderedKeys = merged,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
        diagnostics.emitMutation(source = source, before = before, after = merged)
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
        diagnostics.emitEnabledChanged(key = key, enabled = enabled, source = source)
        if (!enabled) diagnostics.emitHiddenDueToDisabled(key)
        // Intentionally no rail_order_mutation emission here — orderedKeys did not change;
        // the rail_enabled_changed event below carries the actual semantic.
    }

    suspend fun reorderProviderKeys(
        family: RailFamily,
        providerOrder: List<HomeRailKey>,
        source: RailOrderMutationSource,
    ) = reorderProviderKeys(family, providerOrder, source, lastKnownLiveDefinitions)

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
        val before = current.orderedKeys
        persist(current.copy(
            orderedKeys = merged,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
        diagnostics.emitMutation(source = source, before = before, after = merged)
    }

    suspend fun tryMigrate(
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitions: List<HomeRailDefinition>,
    ) = mutationLock.withLock {
        lastKnownLiveDefinitions = liveDefinitions
        knownLiveKeysCache.value = liveDefinitions.map { it.key }.toSet()
        val current = currentForMutation()
        val legacyOrder = layoutPreferenceDataStore.homeCatalogOrderKeys.first().map(::HomeRailKey)
        val legacyDisabled = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().map(::HomeRailKey)
        val migrated = migrateHomeRailOrderState(
            current = current,
            legacyOrder = legacyOrder,
            legacyDisabled = legacyDisabled,
            liveDefinitions = liveDefinitions,
            persistedSyntheticOrder = persistedSyntheticOrder,
            nowMs = clock.millis(),
        )
        if (migrated != current) persist(migrated)
    }

    suspend fun onLiveDefinitionsArrived(
        liveDefinitions: List<HomeRailDefinition>,
    ) = mutationLock.withLock {
        lastKnownLiveDefinitions = liveDefinitions
        knownLiveKeysCache.value = liveDefinitions.map { it.key }.toSet()
        val current = currentForMutation()
        val finalized = finalizeSyntheticFallback(
            current = current,
            liveDefinitions = liveDefinitions,
            nowMs = clock.millis(),
        )
        if (finalized != current) persist(finalized)
    }

    /**
     * Synchronously reconcile the current state with the given live definitions.
     *
     * Reads `lastWrittenState` if populated (the in-memory authoritative copy after any
     * mutation), otherwise falls back to `state.value` (the StateFlow's last decoded value
     * or `HomeRailOrderState.Empty` if the upstream hasn't decoded yet). This is the
     * pipeline-friendly version: callers that need the result inline (e.g., within a
     * `withContext(Dispatchers.Default)` block) avoid the `combine`/`stateIn` round-trip.
     */
    fun reconcileNow(liveDefinitions: List<HomeRailDefinition>): EffectiveHomeRailOrder {
        lastKnownLiveDefinitions = liveDefinitions
        knownLiveKeysCache.value = liveDefinitions.map { it.key }.toSet()
        val current = lastWrittenState ?: state.value
        val result = reconciler.reconcile(current.orderedKeys, current.disabledKeys, liveDefinitions)
        emitReconciledDiagnostics(current, liveDefinitions, result)
        return result
    }

    /**
     * Pipeline-friendly pass-through to emit a `persisted_synthetic_used_as_content_only`
     * event for [key] without exposing the diagnostics sink directly.
     */
    fun emitPersistedSyntheticFallback(key: HomeRailKey) {
        diagnostics.emitPersistedSyntheticUsedAsContentOnly(key)
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
