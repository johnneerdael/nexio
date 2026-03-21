package com.nexio.tv.debug.passthrough

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationHostedAssetStoreTest {

    private val manifestLoader = TransportValidationManifestLoader()

    @Test
    fun `load manifest from hosted validator source`() = runTest {
        val store = createStore()

        val manifest = store.loadManifest()

        assertEquals("2026.03.19", manifest.version)
        assertTrue(manifest.samples.any { it.id == "truehd" })
        assertTrue(manifest.samples.any { it.id == "dtshd" })
    }

    @Test
    fun `prepare sample reuses checksum matching cached files`() = runTest {
        val store = createStore()

        val first = store.prepareSample("truehd")
        val second = store.prepareSample("truehd")

        assertEquals(TransportValidationAssetCacheState.DOWNLOADED, first.assetSource.source.cacheState)
        assertEquals(TransportValidationAssetCacheState.REUSED, second.assetSource.source.cacheState)
        assertEquals(TransportValidationAssetCacheState.REUSED, second.assetSource.reference.cacheState)
        assertTrue(second.sourceFile.exists())
        assertTrue(second.referenceFile.exists())
    }

    @Test(expected = IllegalStateException::class)
    fun `prepare sample fails when hosted asset is missing`() = runTest {
        val store = createStore(missingRemotePath = "truehd.spdif")

        store.prepareSample("truehd")
    }

    private fun createStore(
        missingRemotePath: String? = null,
    ): TransportValidationHostedAssetStore {
        val tempRoot = createTempDirectory("transport-validation-hosted-store-test").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns tempRoot
        val fetcher = FakeTransportValidationAssetFetcher(missingRemotePath)
        return TransportValidationHostedAssetStore(context, fetcher, manifestLoader)
    }

    private class FakeTransportValidationAssetFetcher(
        private val missingRemotePath: String? = null,
    ) : TransportValidationAssetFetcher {
        override fun fetchString(url: String): String {
            val file = repoAsset(url.substringAfterLast('/'))
            return file.readText()
        }

        override fun fetchToFile(url: String, targetFile: File) {
            val remoteName = url.substringAfterLast('/')
            if (missingRemotePath == remoteName) {
                throw IllegalStateException("Missing remote asset $remoteName")
            }
            val sourceFile = repoAsset(remoteName)
            targetFile.outputStream().buffered().use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }
}

private fun repoAsset(name: String): File {
    val direct = File("assets/$name")
    if (direct.exists()) {
        return direct
    }
    val parent = File("../assets/$name")
    if (parent.exists()) {
        return parent
    }
    throw IllegalStateException("Unable to locate repo asset $name from test runtime")
}
