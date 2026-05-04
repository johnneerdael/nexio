package com.nexio.tv.data.local.integration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.integration.IntegrationCacheOwnership
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalIntegrationCacheStoreInvalidateTest {

    private lateinit var db: IntegrationCacheDatabase
    private lateinit var blobStore: IntegrationBlobStore
    private lateinit var store: LocalIntegrationCacheStore
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, IntegrationCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tmpDir = File.createTempFile("cache-invalidate", "").also {
            it.delete()
            it.mkdirs()
        }
        blobStore = IntegrationBlobStore(tmpDir)
        store = LocalIntegrationCacheStore(
            cacheDao = db.cacheDao(),
            blobStore = blobStore
        )
    }

    @After
    fun tearDown() {
        db.close()
        tmpDir.deleteRecursively()
    }

    @Test
    fun delete_evicts_a_previously_written_entry() = runBlocking {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "test.shape",
            operationKey = "test.op",
            cacheKey = "test:cache:key",
            codec = gsonCodec<String>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 0L
            ),
            ownership = IntegrationCacheOwnership.None,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            load = { IntegrationLoadResult.Success("ignored") }
        )

        store.write(spec, "hello")
        assertEquals("hello", store.readFresh(spec))

        val deleted = store.delete(spec)
        assertEquals(true, deleted)
        assertNull(store.readFresh(spec))
    }

    @Test
    fun delete_returns_false_when_key_missing() = runBlocking {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "test.shape",
            operationKey = "test.op",
            cacheKey = "test:does:not:exist",
            codec = gsonCodec<String>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 0L
            ),
            ownership = IntegrationCacheOwnership.None,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            load = { IntegrationLoadResult.Success("ignored") }
        )
        assertEquals(false, store.delete(spec))
    }
}
