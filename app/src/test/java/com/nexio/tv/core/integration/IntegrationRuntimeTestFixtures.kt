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
import java.util.concurrent.ConcurrentHashMap

data class ByteArrayRuntimeFixture(
    val runtime: DefaultIntegrationRuntime,
    val cacheStore: ByteArrayIntegrationCacheStore,
    val backoffManager: IntegrationBackoffManager,
    val auditSink: RecordingIntegrationAuditSink
)

class ByteArrayIntegrationCacheStore : IntegrationCacheStore {
    private val blobs = ConcurrentHashMap<String, ByteArray>()

    override suspend fun <T> readFresh(spec: IntegrationSpec<T>): T? {
        val bytes = blobs[spec.requiredCacheKey] ?: return null
        return spec.codec.decode(bytes)
    }

    override suspend fun <T> readStale(spec: IntegrationSpec<T>): T? {
        val bytes = blobs[spec.requiredCacheKey] ?: return null
        return spec.codec.decode(bytes)
    }

    override suspend fun <T> write(spec: IntegrationSpec<T>, value: T) {
        blobs[spec.requiredCacheKey] = spec.codec.encode(value)
    }

    override suspend fun deleteOwnedMedia(mediaKey: String): Int = 0

    fun contains(cacheKey: String): Boolean = blobs.containsKey(cacheKey)
}

data class RealRuntimeFixture(
    val runtime: DefaultIntegrationRuntime,
    val backoffManager: IntegrationBackoffManager,
    val playbackGate: IntegrationPlaybackGate,
    val backoffDao: IntegrationProviderBackoffDao,
    val cacheDao: IntegrationCacheDao,
    val blobStore: IntegrationBlobStore,
    val cacheStore: LocalIntegrationCacheStore,
    val requestGate: ProviderRequestGate,
    val auditSink: RecordingIntegrationAuditSink
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

class RecordingIntegrationAuditSink : IntegrationAuditSink {
    val events = mutableListOf<IntegrationAuditEvent>()

    override fun record(event: IntegrationAuditEvent) {
        events += event
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
        spec.cacheKey?.let(keys::add)
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

fun passThroughTestRuntime(): IntegrationRuntime =
    object : IntegrationRuntime {
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> =
            when (val result = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(result.value)
                is IntegrationLoadResult.HttpError -> IntegrationFetchResult.Missing
                is IntegrationLoadResult.NetworkError -> IntegrationFetchResult.Missing
            }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            spec.call()

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            spec.open()
    }

fun byteArrayRuntimeFixture(): ByteArrayRuntimeFixture {
    val registry = defaultIntegrationPolicyRegistry()
    val cacheStore = ByteArrayIntegrationCacheStore()
    val backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao())
    val auditSink = RecordingIntegrationAuditSink()
    val runtime = DefaultIntegrationRuntime(
        cacheStore = cacheStore,
        requestGate = ProviderRequestGate(registry),
        backoffManager = backoffManager,
        singleFlight = IntegrationSingleFlight(),
        playbackGate = IntegrationPlaybackGate(),
        registry = registry,
        auditSink = auditSink
    )
    return ByteArrayRuntimeFixture(runtime, cacheStore, backoffManager, auditSink)
}

fun realRuntimeFixture(): RealRuntimeFixture {
    val database = inMemoryIntegrationCacheDatabase()
    val cacheDao = database.cacheDao()
    val backoffDao = database.backoffDao()
    val blobStore = tempIntegrationBlobStore()
    val cacheStore = LocalIntegrationCacheStore(cacheDao, blobStore)
    val registry = defaultIntegrationPolicyRegistry()
    val requestGate = ProviderRequestGate(registry)
    val backoffManager = IntegrationBackoffManager(backoffDao)
    val playbackGate = IntegrationPlaybackGate()
    val auditSink = RecordingIntegrationAuditSink()
    val runtime = DefaultIntegrationRuntime(
        cacheStore = cacheStore,
        requestGate = requestGate,
        backoffManager = backoffManager,
        singleFlight = IntegrationSingleFlight(),
        playbackGate = playbackGate,
        registry = registry,
        auditSink = auditSink
    )
    return RealRuntimeFixture(
        runtime = runtime,
        backoffManager = backoffManager,
        playbackGate = playbackGate,
        backoffDao = backoffDao,
        cacheDao = cacheDao,
        blobStore = blobStore,
        cacheStore = cacheStore,
        requestGate = requestGate,
        auditSink = auditSink
    )
}

class RecordingTraceSink : com.nexio.tv.core.trace.RuntimeTraceSink {
    val events = mutableListOf<com.nexio.tv.core.trace.TraceEventEnvelope<*>>()
    override fun emit(event: com.nexio.tv.core.trace.TraceEventEnvelope<*>) {
        events += event
    }
    override fun eventsWritten(): Long = events.size.toLong()
    override fun eventsDropped(): Long = 0L
}

class InMemoryIntegrationProviderBackoffDao : IntegrationProviderBackoffDao {
    private val values = linkedMapOf<String, IntegrationProviderBackoffEntity>()

    override suspend fun upsert(entity: IntegrationProviderBackoffEntity) {
        values[entity.key] = entity
    }

    override suspend fun get(provider: String, scopeKey: String): IntegrationProviderBackoffEntity? =
        values["$provider:$scopeKey"]

    override suspend fun clear(provider: String, scopeKey: String) {
        values.remove("$provider:$scopeKey")
    }
}
