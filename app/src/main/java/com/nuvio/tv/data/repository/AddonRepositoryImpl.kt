package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

class AddonRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val preferences: AddonPreferences
) : AddonRepository {

    private val manifestCache = mutableMapOf<String, Addon>()

    override fun getInstalledAddons(): Flow<List<Addon>> =
        preferences.installedAddonUrls.flatMapLatest { urls ->
            flow {
                val cached = urls.mapNotNull { manifestCache[it.trimEnd('/')] }
                if (cached.isNotEmpty()) {
                    emit(cached)
                }

                val fresh = coroutineScope {
                    urls.map { url ->
                        async {
                            when (val result = fetchAddon(url)) {
                                is NetworkResult.Success -> result.data
                                else -> manifestCache[url.trimEnd('/')]
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                if (fresh != cached) {
                    emit(fresh)
                }
            }.flowOn(Dispatchers.IO)
        }

    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val manifestUrl = "$cleanBaseUrl/manifest.json"

        return when (val result = safeApiCall { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl)
                manifestCache[cleanBaseUrl] = addon
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun addAddon(url: String) {
        val cleanUrl = url.trimEnd('/')
        preferences.addAddon(cleanUrl)
    }

    override suspend fun removeAddon(url: String) {
        val cleanUrl = url.trimEnd('/')
        manifestCache.remove(cleanUrl)
        preferences.removeAddon(cleanUrl)
    }

    override suspend fun setAddonOrder(urls: List<String>) {
        preferences.setAddonOrder(urls)
    }
}
