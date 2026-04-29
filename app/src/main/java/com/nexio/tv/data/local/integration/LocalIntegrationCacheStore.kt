package com.nexio.tv.data.local.integration

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheOwnership
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

        val entry = cacheDao.getCacheEntry(spec.requiredCacheKey) ?: return null
        if (entry.expiresAtEpochMs < nowMsProvider()) return null

        // F-D-02: tolerate FileNotFoundException, short reads, decode failures during concurrent writes
        return runCatching {
            val file = blobStore.fileFor(entry.blobPath)
            if (!file.exists()) return@runCatching null
            spec.codec.decode(file.readBytes())
        }.getOrNull()
    }

    override suspend fun <T> readStale(spec: IntegrationSpec<T>): T? {
        if (spec.cachePolicy !is IntegrationCachePolicy.CacheFirst) return null

        val entry = cacheDao.getCacheEntry(spec.requiredCacheKey) ?: return null
        if (entry.staleUntilEpochMs < nowMsProvider()) return null

        // F-D-02: tolerate FileNotFoundException, short reads, decode failures during concurrent writes
        return runCatching {
            val file = blobStore.fileFor(entry.blobPath)
            if (!file.exists()) return@runCatching null
            spec.codec.decode(file.readBytes())
        }.getOrNull()
    }

    override suspend fun <T> write(spec: IntegrationSpec<T>, value: T) {
        val policy = spec.cachePolicy as? IntegrationCachePolicy.CacheFirst ?: return
        val now = nowMsProvider()
        val freshUntil = now + policy.ttlMs
        val staleUntil = freshUntil + policy.staleAfterExpiryMs
        val cacheKey = spec.requiredCacheKey
        val blobPath = cacheKey.replace(':', '/') + ".bin"
        val finalFile = blobStore.fileFor(blobPath)
        val tmpFile = blobStore.fileFor("$blobPath.tmp")
        val ownerToken = when (val ownership = spec.ownership) {
            IntegrationCacheOwnership.None -> null
            is IntegrationCacheOwnership.Media -> ownership.mediaKey
        }

        // F-D-02: write to .tmp first, then atomically rename + upsert in a Room @Transaction.
        finalFile.parentFile?.mkdirs()
        tmpFile.writeBytes(spec.codec.encode(value))
        cacheDao.atomicRenameAndUpsert(
            tmpFile = tmpFile,
            finalFile = finalFile,
            entity = IntegrationCacheEntity(
                cacheKey = cacheKey,
                provider = spec.provider.name,
                scopeKey = spec.scope.storageKey,
                blobPath = blobPath,
                mimeType = spec.codec.mimeType,
                expiresAtEpochMs = freshUntil,
                staleUntilEpochMs = staleUntil,
                updatedAtEpochMs = now,
                ownerToken = ownerToken
            )
        )
    }

    override suspend fun deleteOwnedMedia(mediaKey: String): Int {
        val ownedEntries = cacheDao.findByMediaKey(mediaKey)
        val blobPaths = ownedEntries.map { it.blobPath }
        // F2-D-08: DAO delete first so a process kill leaves a dangling blob
        // (reapable by IntegrationOrphanCleanupService) instead of a dangling DAO row
        // pointing at a missing blob.
        val deleted = cacheDao.deleteByMediaKey(mediaKey)
        blobPaths.forEach { blobStore.delete(it) }
        return deleted
    }
}
