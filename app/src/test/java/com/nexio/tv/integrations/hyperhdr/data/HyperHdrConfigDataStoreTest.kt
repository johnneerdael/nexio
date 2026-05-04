package com.nexio.tv.integrations.hyperhdr.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HyperHdrConfigDataStoreTest {

    private lateinit var file: File
    private lateinit var store: HyperHdrConfigDataStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        file = ctx.preferencesDataStoreFile("hyperhdr_test_${System.nanoTime()}")
        val ds = PreferenceDataStoreFactory.create(produceFile = { file })
        store = HyperHdrConfigDataStore(ds)
    }

    @After
    fun tearDown() { file.delete() }

    @Test
    fun `default config is disabled with empty host`() = runTest {
        assertThat(store.config.first()).isEqualTo(HyperHdrConfig())
    }

    @Test
    fun `update persists each field independently`() = runTest {
        store.update { it.copy(host = "192.168.1.10") }
        store.update { it.copy(port = 19444) }
        store.update { it.copy(priority = 50) }
        store.update { it.copy(enabled = true) }
        val cfg = store.config.first()
        assertThat(cfg).isEqualTo(HyperHdrConfig(
            enabled = true, host = "192.168.1.10", port = 19444, priority = 50,
        ))
    }

    @Test
    fun `default config has Auto hdrMode and 8090 jsonPort`() = runTest {
        val cfg = store.config.first()
        assertThat(cfg.hdrMode).isEqualTo(HdrMode.Auto)
        assertThat(cfg.jsonPort).isEqualTo(8090)
    }

    @Test
    fun `update persists hdrMode and jsonPort independently`() = runTest {
        store.update { it.copy(hdrMode = HdrMode.ForceSdr) }
        store.update { it.copy(jsonPort = 19500) }
        val cfg = store.config.first()
        assertThat(cfg.hdrMode).isEqualTo(HdrMode.ForceSdr)
        assertThat(cfg.jsonPort).isEqualTo(19500)
    }

    @Test
    fun `default config has empty jsonToken`() = runTest {
        assertThat(store.config.first().jsonToken).isEqualTo("")
    }

    @Test
    fun `update persists jsonToken independently`() = runTest {
        store.update { it.copy(jsonToken = "abc123def456") }
        assertThat(store.config.first().jsonToken).isEqualTo("abc123def456")
    }
}
