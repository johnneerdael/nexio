package com.nexio.tv.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.local.integration.IntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import com.nexio.tv.data.local.integration.IntegrationCacheDatabase
import com.nexio.tv.data.local.integration.IntegrationCacheEntity
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffEntity
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import java.nio.file.Files

data class RealRuntimeFixture(
    val runtime: DefaultIntegrationRuntime,
    val backoffManager: IntegrationBackoffManager,
    val backoffDao: IntegrationProviderBackoffDao,
    val cacheDao: IntegrationCacheDao,
    val blobStore: IntegrationBlobStore,
    val cacheStore: LocalIntegrationCacheStore,
    val requestGate: ProviderRequestGate
) {
    suspend fun <T> seedCache(
        cacheKey: String,
        codec: IntegrationCodec<T>,
        value: T,
        freshForMs: Long,
        staleAfterMs: Long
    ) {
        val blobPath = cacheKey.replace(':', '/') + ".seed"
        val now = System.currentTimeMillis()
        blobStore.fileFor(blobPath).writeBytes(codec.encode(value))
        cacheDao.upsertCacheEntry(
            IntegrationCacheEntity(
                cacheKey = cacheKey,
                provider = cacheKey.substringBefore(':').uppercase(),
                scopeKey = "global",
                blobPath = blobPath,
                mimeType = codec.mimeType,
                expiresAtEpochMs = now + freshForMs,
                staleUntilEpochMs = now + freshForMs + staleAfterMs,
                updatedAtEpochMs = now,
                ownerToken = null
            )
        )
    }
}

class RecordingIntegrationRuntime<T>(
    private val successValue: T? = null,
    private val nextResult: IntegrationFetchResult<T>? = null,
    private val nextCallResult: IntegrationCallResult<T>? = null,
    private val nextStreamHandle: IntegrationStreamHandle<T>? = null
) : IntegrationRuntime {
    val keys = mutableListOf<String>()
    val specs = mutableListOf<IntegrationSpec<*>>()
    val callSpecs = mutableListOf<IntegrationCallSpec<*>>()
    val streamSpecs = mutableListOf<IntegrationStreamSpec<*>>()

    override suspend fun <R> get(
        spec: IntegrationSpec<R>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<R> {
        keys += spec.cacheKey
        specs += spec
        @Suppress("UNCHECKED_CAST")
        return nextResult as? IntegrationFetchResult<R>
            ?: successValue?.let { IntegrationFetchResult.Updated(it as R) }
            ?: IntegrationFetchResult.Missing
    }

    override suspend fun <R> call(spec: IntegrationCallSpec<R>): IntegrationCallResult<R> {
        callSpecs += spec
        @Suppress("UNCHECKED_CAST")
        return nextCallResult as? IntegrationCallResult<R>
            ?: successValue?.let { IntegrationCallResult.Success(it as R) }
            ?: IntegrationCallResult.Missing
    }

    override suspend fun <R> open(spec: IntegrationStreamSpec<R>): IntegrationStreamHandle<R>? {
        streamSpecs += spec
        @Suppress("UNCHECKED_CAST")
        return nextStreamHandle as? IntegrationStreamHandle<R>
    }
}

fun inMemoryIntegrationCacheDatabase(): IntegrationCacheDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(
        context,
        IntegrationCacheDatabase::class.java
    ).allowMainThreadQueries().build()
}

fun tempIntegrationBlobStore(): IntegrationBlobStore =
    IntegrationBlobStore(Files.createTempDirectory("integration-cache-test").toFile())

fun realRuntimeFixture(): RealRuntimeFixture {
    val database = inMemoryIntegrationCacheDatabase()
    val cacheDao = database.cacheDao()
    val backoffDao = database.backoffDao()
    val blobStore = tempIntegrationBlobStore()
    val cacheStore = LocalIntegrationCacheStore(cacheDao, blobStore)
    val registry = defaultIntegrationPolicyRegistry()
    val requestGate = ProviderRequestGate(registry)
    val backoffManager = IntegrationBackoffManager(backoffDao)
    val runtime = DefaultIntegrationRuntime(
        cacheStore = cacheStore,
        requestGate = requestGate,
        backoffManager = backoffManager,
        singleFlight = IntegrationSingleFlight(),
        playbackGate = IntegrationPlaybackGate(),
        registry = registry
    )
    return RealRuntimeFixture(
        runtime = runtime,
        backoffManager = backoffManager,
        backoffDao = backoffDao,
        cacheDao = cacheDao,
        blobStore = blobStore,
        cacheStore = cacheStore,
        requestGate = requestGate
    )
}

class InMemoryIntegrationProviderBackoffDao : IntegrationProviderBackoffDao {
    private val values = linkedMapOf<String, IntegrationProviderBackoffEntity>()

    override suspend fun upsert(entity: IntegrationProviderBackoffEntity) {
        values[entity.key] = entity
    }

    override suspend fun get(provider: String, scopeKey: String): IntegrationProviderBackoffEntity? =
        values["$provider:$scopeKey"]
}
