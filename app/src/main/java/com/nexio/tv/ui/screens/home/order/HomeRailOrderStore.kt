package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

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
        val current = state.value
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
        val current = state.value
        val newDisabled = if (enabled) current.disabledKeys - key else current.disabledKeys + key
        if (newDisabled == current.disabledKeys) return@withLock
        persist(current.copy(
            disabledKeys = newDisabled,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
    }

    private suspend fun persist(state: HomeRailOrderState) {
        layoutPreferenceDataStore.setHomeRailOrderStateJson(codec.encode(state))
    }
}
