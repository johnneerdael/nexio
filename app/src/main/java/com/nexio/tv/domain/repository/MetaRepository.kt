package com.nexio.tv.domain.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String,
        cacheOnDisk: Boolean = true,
        writeToDisk: Boolean = true,
        origin: String = "default"
    ): Flow<NetworkResult<Meta>>

    /**
     * Fetches Meta for an item that originated from a specific addon catalog rail.
     * The addon parameter is required by the type signature — callers without a
     * verified addon origin MUST use [com.nexio.tv.core.metadata.router.MetadataRouterFacade.resolveRequest]
     * instead.
     *
     * Allowed callers are listed in the allow-list at:
     *   app/src/test/resources/architecture/meta_repository_fanout_allowlist.txt
     */
    fun hydrateAddonOriginItem(
        addon: Addon,
        type: String,
        id: String,
        cacheOnDisk: Boolean = true,
        writeToDisk: Boolean = true,
        origin: String = "default"
    ): Flow<NetworkResult<Meta>>

    fun clearCache()
}
