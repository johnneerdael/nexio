package com.nexio.tv.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.network.safeApiCall
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.core.sync.normalizeAddonInstallUrl
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.mapper.toDomain
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.sync.AddonSyncService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddonRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val preferences: AddonPreferences,
    private val addonSyncService: AddonSyncService,
    private val authManager: AuthManager,
    @ApplicationContext private val context: Context
) : AddonRepository {

    companion object {
        private const val TAG = "AddonRepository"
        private const val MANIFEST_CACHE_PREFS = "addon_manifest_cache"
        private const val MANIFEST_CACHE_KEY = "manifests"
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private val remoteSyncInProgressCount = AtomicInteger(0)
    val isSyncingFromRemote: Boolean
        get() = remoteSyncInProgressCount.get() > 0

    fun beginRemoteSyncReconcile() {
        remoteSyncInProgressCount.incrementAndGet()
    }

    fun endRemoteSyncReconcile() {
        remoteSyncInProgressCount.updateAndGet { count ->
            if (count > 0) count - 1 else 0
        }
    }

    private fun canonicalizeUrl(url: String): String {
        return normalizeAddonInstallUrl(url)
    }

    private fun safeCanonicalizeUrl(url: String, label: String): String? {
        return runCatching { canonicalizeUrl(url) }
            .onFailure { error ->
                Log.w(TAG, "Dropping malformed addon URL from $label: ${sanitizeUrlForLogs(url)}", error)
            }
            .getOrNull()
    }

    private fun normalizeUrl(url: String): String = canonicalizeUrl(url).lowercase()

    private fun triggerRemoteSync() {
        if (isSyncingFromRemote) {
            Log.d(TAG, "triggerRemoteSync: skipped (syncing from remote)")
            return
        }
        if (!authManager.hasSyncSession) {
            Log.d(TAG, "triggerRemoteSync: skipped (no sync session)")
            return
        }
        Log.d(TAG, "triggerRemoteSync: scheduling push in 500ms")
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            val result = addonSyncService.pushToRemote()
            Log.d(TAG, "triggerRemoteSync: push result=${result.isSuccess} ${result.exceptionOrNull()?.message ?: ""}")
        }
    }

    private val gson = Gson()
    private val manifestCache = ConcurrentHashMap<String, Addon>()

    init {
        loadManifestCacheFromDisk()
    }

    private fun loadManifestCacheFromDisk() {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(MANIFEST_CACHE_KEY, null) ?: return
            val type = object : TypeToken<Map<String, Addon>>() {}.type
            val cached: Map<String, Addon> = gson.fromJson(json, type) ?: return
            manifestCache.putAll(cached)
            Log.d(TAG, "Loaded ${cached.size} cached manifests from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load manifest cache from disk", e)
        }
    }

    private fun persistManifestCacheToDisk() {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(MANIFEST_CACHE_KEY, gson.toJson(manifestCache.toMap())).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist manifest cache to disk", e)
        }
    }

    override fun getInstalledAddons(): Flow<List<Addon>> =
        preferences.installedAddons.flatMapLatest { addonConfigs ->
            flow {
                val validConfigs = addonConfigs.mapNotNull { addon ->
                    safeCanonicalizeUrl(addon.url, "preferences flow")?.let { normalized ->
                        addon.copy(url = normalized)
                    }
                }

                // Emit cached addons immediately (now includes disk-persisted cache)
                val cached = validConfigs.mapNotNull { addonConfig ->
                    manifestCache[addonConfig.url]?.copy(parserPreset = addonConfig.parserPreset)
                }
                if (cached.isNotEmpty()) {
                    emit(applyDisplayNames(cached))
                }

                val fresh = coroutineScope {
                    validConfigs.map { addonConfig ->
                        async {
                            when (val result = fetchAddon(addonConfig.url, addonConfig.parserPreset)) {
                                is NetworkResult.Success -> result.data
                                else -> manifestCache[canonicalizeUrl(addonConfig.url)]
                                    ?.copy(parserPreset = addonConfig.parserPreset)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

               
                if (fresh != cached || cached.isEmpty()) {
                    emit(applyDisplayNames(fresh))
                }
            }.flowOn(Dispatchers.IO)
        }

    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> {
        return fetchAddon(baseUrl, AddonParserPreset.GENERIC)
    }

    private suspend fun fetchAddon(
        baseUrl: String,
        parserPreset: AddonParserPreset
    ): NetworkResult<Addon> {
        val cleanBaseUrl = canonicalizeUrl(baseUrl)
        val manifestUrl = buildAddonRequestUrl(cleanBaseUrl, "manifest.json")

        return when (val result = safeApiCall { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl).copy(parserPreset = parserPreset)
                manifestCache[cleanBaseUrl] = addon
                persistManifestCacheToDisk()
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Failed to fetch addon manifest for url=${sanitizeUrlForLogs(cleanBaseUrl)} code=${result.code} message=${result.message}"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun addAddon(url: String, parserPreset: AddonParserPreset) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.addAddon(cleanUrl, parserPreset)
        triggerRemoteSync()
    }

    override suspend fun removeAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        manifestCache.remove(cleanUrl)
        preferences.removeAddon(cleanUrl)
        triggerRemoteSync()
    }

    override suspend fun setAddonOrder(urls: List<String>) {
        preferences.setAddonOrder(urls)
        triggerRemoteSync()
    }

    override suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.updateAddonParserPreset(cleanUrl, parserPreset)
        manifestCache[cleanUrl]?.let { cached ->
            manifestCache[cleanUrl] = cached.copy(parserPreset = parserPreset)
            persistManifestCacheToDisk()
        }
        triggerRemoteSync()
    }

    suspend fun reconcileWithRemoteAddonConfigs(
        remoteAddons: List<AddonPreferences.AddonInstallConfig>,
        removeMissingLocal: Boolean = true
    ) {
        val normalizedRemote = remoteAddons.mapNotNull { addon ->
            safeCanonicalizeUrl(addon.url, "remote reconcile")?.let { normalized ->
                addon.copy(url = normalized)
            }
        }.distinctBy { normalizeUrl(it.url) }
        val remoteSet = normalizedRemote.map { normalizeUrl(it.url) }.toSet()

        val initialLocalAddons = preferences.installedAddons.first()
        val initialLocalSet = initialLocalAddons.map { normalizeUrl(it.url) }.toSet()
        val shouldRemoveMissingLocal = if (removeMissingLocal && normalizedRemote.isEmpty() && initialLocalAddons.isNotEmpty()) {
            Log.w(
                TAG,
                "reconcileWithRemoteAddonConfigs: remote list empty while local has ${initialLocalAddons.size} entries; preserving local addons"
            )
            false
        } else {
            removeMissingLocal
        }

        if (shouldRemoveMissingLocal) {
            initialLocalAddons
                .filter { normalizeUrl(it.url) !in remoteSet }
                .forEach { removeAddon(it.url) }
        }

        normalizedRemote
            .filter { normalizeUrl(it.url) !in initialLocalSet }
            .forEach { addAddon(it.url, it.parserPreset) }

        val currentAddons = preferences.installedAddons.first()
        val currentByNormalizedUrl = linkedMapOf<String, AddonPreferences.AddonInstallConfig>()
        currentAddons.forEach { addon ->
            currentByNormalizedUrl.putIfAbsent(normalizeUrl(addon.url), addon.copy(url = canonicalizeUrl(addon.url)))
        }
        val remoteOrdered = normalizedRemote
            .mapNotNull { remote ->
                currentByNormalizedUrl[normalizeUrl(remote.url)]?.copy(parserPreset = remote.parserPreset)
                    ?: remote
            }
        val extras = currentAddons
            .map { it.copy(url = canonicalizeUrl(it.url)) }
            .filter { normalizeUrl(it.url) !in remoteSet }

        val reordered = if (shouldRemoveMissingLocal) remoteOrdered else remoteOrdered + extras
        if (reordered != currentAddons.map { it.copy(url = canonicalizeUrl(it.url)) }) {
            preferences.setAddonConfigs(reordered)
        }
    }

    private fun applyDisplayNames(addons: List<Addon>): List<Addon> {
        val nameCounts = mutableMapOf<String, Int>()
        for (addon in addons) {
            nameCounts[addon.name] = (nameCounts[addon.name] ?: 0) + 1
        }

        val nameCounters = mutableMapOf<String, Int>()
        return addons.map { addon ->
            if ((nameCounts[addon.name] ?: 0) <= 1) {
                addon.copy(displayName = addon.name)
            } else {
                val occurrence = (nameCounters[addon.name] ?: 0) + 1
                nameCounters[addon.name] = occurrence
                if (occurrence == 1) {
                    addon.copy(displayName = addon.name)
                } else {
                    addon.copy(displayName = "${addon.name} ($occurrence)")
                }
            }
        }
    }
}
