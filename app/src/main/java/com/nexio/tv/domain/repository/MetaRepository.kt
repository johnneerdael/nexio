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

    /**
     * Reads cached metadata for the given [type] and [id] directly from the disk cache without
     * triggering any network fan-out. Returns null when no cached entry exists.
     *
     * Use this instead of [hydrateAddonOriginItem] when you only need enriched metadata that has
     * already been fetched and persisted (e.g. warm-start screensaver slides).
     */
    suspend fun readCachedMeta(type: String, id: String): Meta?

    fun clearCache()
}
