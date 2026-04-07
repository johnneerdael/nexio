package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.test.utils.DataSourceContractTest
import androidx.media3.test.utils.TestUtil
import com.google.common.collect.ImmutableList
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Phase 1 contract test: exercises ParallelRangeDataSource against the Media3
 * DataSourceContractTest suite. Tests that fail here document the current contract
 * gap before the refactor. Do NOT @Ignore failing tests.
 */
@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceContractTest : DataSourceContractTest() {

    companion object {
        // 512 KiB deterministic payload — large enough to span multiple chunks at small chunk size
        private val RESOURCE_BYTES: ByteArray = ByteArray(512 * 1024) { (it and 0xFF).toByte() }
        // Small chunk size so even 512 KiB covers multi-chunk parallel paths
        private const val TEST_CHUNK_SIZE = 64 * 1024L   // 64 KiB
        private const val TEST_PARALLEL = 2
    }

    private lateinit var server: MockWebServer
    private lateinit var sharedClient: OkHttpClient

    @Before
    fun startServer() {
        server = MockRangeServer.start(RESOURCE_BYTES)
        // Share one OkHttpClient across the many createDataSource() calls Media3's
        // contract test base class makes per test, to avoid Robolectric connection-pool churn.
        sharedClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    override fun createDataSource(): DataSource {
        return ParallelRangeDataSource(
            upstreamFactory = OkHttpDataSource.Factory(sharedClient),
            parallelConnections = TEST_PARALLEL,
            chunkSize = TEST_CHUNK_SIZE,
        )
    }

    override fun getTestResources(): ImmutableList<DataSourceContractTest.TestResource> {
        val uri = Uri.parse(server.url("/media.bin").toString())
        return ImmutableList.of(
            DataSourceContractTest.TestResource.Builder()
                .setName("512KiB rangeable resource")
                .setUri(uri)
                .setExpectedBytes(RESOURCE_BYTES)
                .build()
        )
    }

    override fun getNotFoundUri(): Uri =
        Uri.parse(server.url("/notfound/missing.bin").toString())
}
