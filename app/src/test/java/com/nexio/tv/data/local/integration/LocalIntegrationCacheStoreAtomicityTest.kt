package com.nexio.tv.data.local.integration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.gsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * F-D-02 atomicity pin: a tight-loop reader running concurrently with a flip-flop writer
 * MUST always observe one of the known-good values and never a corrupt-decode value or
 * mismatched DAO/blob row.
 *
 * On the pre-fix non-atomic impl (file.writeBytes then DAO upsert in two steps), the
 * reader sometimes catches the file partially-written or in a state inconsistent with the
 * DAO row, leading to JsonSyntaxException, a thrown exception, or a decode of a torn
 * payload. After Task 8's tmp+rename + Room @Transaction fix, this test is
 * deterministic-PASS.
 */
@RunWith(RobolectricTestRunner::class)
class LocalIntegrationCacheStoreAtomicityTest {

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
        tmpDir = File.createTempFile("cache-atomicity", "").also {
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
    fun `concurrent writer + tight-loop reader never returns corrupt decode`() = runBlocking {
        val spec = sampleStringSpec(cacheKey = "atomic:test:1")
        // Pad payloads so the file write is large enough for a torn read to be observable.
        val payloadA = "value-A".padEnd(64 * 1024, 'A')
        val payloadB = "value-B".padEnd(64 * 1024, 'B')
        val initial = "initial-value".padEnd(64 * 1024, 'I')
        store.write(spec, StringHolder(initial))

        val knownValues = setOf(initial, payloadA, payloadB)
        val errors = mutableListOf<String>()
        coroutineScope {
            val writer = async(Dispatchers.IO) {
                repeat(200) { i ->
                    store.write(spec, StringHolder(if (i % 2 == 0) payloadA else payloadB))
                }
            }
            val reader = async(Dispatchers.IO) {
                repeat(5_000) {
                    val observed: StringHolder? = try {
                        store.readFresh(spec)
                    } catch (t: Throwable) {
                        errors += "decode threw: ${t.javaClass.simpleName}: ${t.message}"
                        null
                    }
                    if (observed != null && observed.value !in knownValues) {
                        errors += "observed unknown value of length ${observed.value.length}"
                    }
                }
            }
            writer.await()
            reader.await()
        }
        assertTrue(
            "F-D-02: reader saw ${errors.size} torn/corrupt observations: " +
                errors.take(5).joinToString(" | "),
            errors.isEmpty()
        )
    }

    private data class StringHolder(val value: String)

    private fun sampleStringSpec(cacheKey: String): IntegrationSpec<StringHolder> =
        IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "test.atomic",
            operationKey = "test.atomic",
            cacheKey = cacheKey,
            codec = gsonCodec(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(60_000L, 60_000L),
            ownership = com.nexio.tv.core.integration.IntegrationCacheOwnership.None,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            load = { IntegrationLoadResult.Success(StringHolder("loaded")) }
        )
}
