package com.nexio.tv.data.local.integration

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheStore
import com.nexio.tv.core.integration.IntegrationSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalIntegrationCacheStore @Inject constructor(
    private val cacheDao: IntegrationCacheDao,
    private val blobStore: IntegrationBlobStore
) : IntegrationCacheStore {
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    override suspend fun <T> readFresh(spec: IntegrationSpec<T>): T? {
        if (spec.cachePolicy !is IntegrationCachePolicy.CacheFirst) return null

        val entry = cacheDao.getCacheEntry(spec.cacheKey) ?: return null
        if (entry.expiresAtEpochMs < nowMsProvider()) return null

        val file = blobStore.fileFor(entry.blobPath)
        if (!file.exists()) return null

        return spec.codec.decode(file.readBytes())
    }

    override suspend fun <T> readStale(spec: IntegrationSpec<T>): T? {
        if (spec.cachePolicy !is IntegrationCachePolicy.CacheFirst) return null

        val entry = cacheDao.getCacheEntry(spec.cacheKey) ?: return null
        if (entry.staleUntilEpochMs < nowMsProvider()) return null

        val file = blobStore.fileFor(entry.blobPath)
        if (!file.exists()) return null

        return spec.codec.decode(file.readBytes())
    }

    override suspend fun <T> write(spec: IntegrationSpec<T>, value: T) {
        val policy = spec.cachePolicy as? IntegrationCachePolicy.CacheFirst ?: return
        val now = nowMsProvider()
        val freshUntil = now + policy.ttlMs
        val staleUntil = freshUntil + policy.staleAfterExpiryMs
        val blobPath = spec.cacheKey.replace(':', '/') + ".bin"
        val file = blobStore.fileFor(blobPath)

        file.writeBytes(spec.codec.encode(value))
        cacheDao.upsertCacheEntry(
            IntegrationCacheEntity(
                cacheKey = spec.cacheKey,
                provider = spec.provider.name,
                scopeKey = spec.scope.storageKey,
                blobPath = blobPath,
                mimeType = spec.codec.mimeType,
                expiresAtEpochMs = freshUntil,
                staleUntilEpochMs = staleUntil,
                updatedAtEpochMs = now,
                ownerToken = null
            )
        )
    }
}
