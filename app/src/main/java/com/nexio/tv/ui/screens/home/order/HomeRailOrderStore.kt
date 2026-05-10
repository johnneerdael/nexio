package com.nexio.tv.ui.screens.home.order

import android.content.Context
import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.di.ApplicationScope
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
class HomeRailOrderStore private constructor(
    private val snapshotDir: File,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val codec: HomeRailOrderStateCodec,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val diagnostics: HomeRailOrderDiagnosticsSink,
    private val profileManager: ProfileManager,
    private val reconciler: HomeRailOrderReconciler = HomeRailOrderReconciler(),
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        layoutPreferenceDataStore: LayoutPreferenceDataStore,
        codec: HomeRailOrderStateCodec,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
        diagnostics: HomeRailOrderDiagnosticsSink,
        profileManager: ProfileManager,
    ) : this(
        snapshotDir = File(context.filesDir, SNAPSHOT_DIR),
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        codec = codec,
        clock = clock,
        scope = scope,
        diagnostics = diagnostics,
        profileManager = profileManager,
        reconciler = HomeRailOrderReconciler(),
    )

    /**
     * Test constructor — bypasses Android context. Defaults [snapshotDir] to
     * an OS-managed temp directory unique to this process invocation so unit
     * tests under JVM (without Robolectric) can exercise file persistence
     * without an `@ApplicationContext`. Production code paths must use the
     * `@Inject` constructor above; this overload exists only for the test
     * suite under `app/src/test`.
     */
    constructor(
        layoutPreferenceDataStore: LayoutPreferenceDataStore,
        codec: HomeRailOrderStateCodec,
        clock: Clock,
        scope: CoroutineScope,
        diagnostics: HomeRailOrderDiagnosticsSink,
        profileManager: ProfileManager,
        reconciler: HomeRailOrderReconciler = HomeRailOrderReconciler(),
    ) : this(
        snapshotDir = Files.createTempDirectory("home-rail-order-test").toFile().also { it.deleteOnExit() },
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        codec = codec,
        clock = clock,
        scope = scope,
        diagnostics = diagnostics,
        profileManager = profileManager,
        reconciler = reconciler,
    )

    companion object {
        private const val TAG = "HomeRailOrderStore"
        private const val SNAPSHOT_DIR = "home-rail-order-v1"
    }

    private val mutationLock = Mutex()
    private val knownLiveKeysCache = MutableStateFlow<Set<HomeRailKey>>(emptySet())
    private var lastWrittenState: HomeRailOrderState? = null
    private var lastKnownLiveDefinitions: List<HomeRailDefinition> = emptyList()

    /**
     * File-backed typed StateFlow. CLAUDE.md hard rule #3: the previous
     * implementation stored the rail order state as a 45 KiB JSON String in
     * Jetpack DataStore Preferences; the
     * `androidx.datastore.preferences.core.MutablePreferences.preferencesMap`
     * pinned the entire String for the lifetime of every Flow collection
     * (heap-confirmed 2026-05-10 ANR investigation, retainer chain
     * `MutablePreferences.preferencesMap → Data.value →
     * StateFlowImpl$collect$1.L$4`). Now backed by a file
     * (`filesDir/home-rail-order-v1/p<profileId>.json`) plus this in-memory
     * typed [MutableStateFlow] so the JSON String only lives for the duration
     * of the streaming parse/serialize call.
     *
     * Read flow: per-profile load on activation, streaming JsonReader →
     * codec.decodeFromReader → [MutableStateFlow.value]. No String
     * materialisation.
     *
     * Write flow: [persist] streams the typed state to a temp file via
     * JsonWriter + atomic rename, then updates [MutableStateFlow.value]. The
     * legacy DataStore `setHomeRailOrderStateJson` setter is no longer called.
     */
    private val _state: MutableStateFlow<HomeRailOrderState> =
        MutableStateFlow(HomeRailOrderState.Empty)
    val state: StateFlow<HomeRailOrderState> = _state.asStateFlow()

    init {
        scope.launch {
            var lastSeenProfileId: Int? = null
            profileManager.activeProfileId.collect { profileId ->
                val loaded = mutationLock.withLock { loadSnapshotForProfile(profileId) }
                if (lastSeenProfileId != null && lastSeenProfileId != profileId) {
                    mutationLock.withLock {
                        lastWrittenState = loaded
                        lastKnownLiveDefinitions = emptyList()
                    }
                } else {
                    mutationLock.withLock { lastWrittenState = loaded }
                }
                _state.value = loaded
                lastSeenProfileId = profileId
            }
        }
    }

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
        // First mutation before init's collector has loaded the file: load
        // synchronously here so we don't observe HomeRailOrderState.Empty and
        // overwrite persisted state.
        val profileId = profileManager.activeProfileId.value
        val initial = loadSnapshotForProfile(profileId)
        lastWrittenState = initial
        _state.value = initial
        return initial
    }

    private suspend fun persist(state: HomeRailOrderState) {
        val profileId = profileManager.activeProfileId.value
        val file = snapshotFileFor(profileId)
        runCatching {
            file.parentFile?.mkdirs()
            writeSnapshotToFile(state, file)
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist home rail order state to file", error)
        }
        lastWrittenState = state
        _state.value = state
    }

    private fun snapshotFileFor(profileId: Int): File {
        if (!snapshotDir.exists()) snapshotDir.mkdirs()
        return File(snapshotDir, "p${profileId.coerceAtLeast(1)}.json")
    }

    private suspend fun loadSnapshotForProfile(profileId: Int): HomeRailOrderState {
        val file = snapshotFileFor(profileId)
        return if (file.exists()) {
            streamReadSnapshot(file) ?: HomeRailOrderState.Empty
        } else {
            migrateLegacySnapshotToFile(profileId, file)
        }
    }

    private fun streamReadSnapshot(file: File): HomeRailOrderState? {
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            null
                        } else {
                            codec.decodeFromReader(reader)
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to stream-read home rail order state", error)
        }.getOrNull()
    }

    private fun writeSnapshotToFile(state: HomeRailOrderState, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    codec.encodeToWriter(state, writer)
                }
            }
        }
        Files.move(
            tempFile.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    /**
     * One-time legacy migration: when the file does not yet exist but the
     * legacy DataStore preference key is populated, decode the legacy String
     * once via the existing codec.decode path, write it to file, then clear
     * the DataStore key so the next launch sees only the file. The legacy
     * read pays the StringReader cost ONE TIME per device per profile during
     * migration; subsequent reads stream from the file.
     */
    @Suppress("DEPRECATION")
    private suspend fun migrateLegacySnapshotToFile(
        profileId: Int,
        target: File,
    ): HomeRailOrderState {
        val legacy = layoutPreferenceDataStore.homeRailOrderStateJson.first()
        if (legacy.isNullOrBlank()) return HomeRailOrderState.Empty
        val decoded = codec.decode(legacy)
        runCatching {
            target.parentFile?.mkdirs()
            writeSnapshotToFile(decoded, target)
        }.onFailure { error ->
            Log.w(TAG, "Failed to migrate legacy home rail order state to file", error)
        }
        runCatching { layoutPreferenceDataStore.clearLegacyHomeRailOrderStateJson() }
            .onFailure { error ->
                Log.w(TAG, "Failed to clear legacy DataStore home rail order state", error)
            }
        return decoded
    }
}
