package com.nexio.tv.ui.screens.account

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountLinkedDevicesContractTest {
    private val accountScreen = File("app/src/main/java/com/nexio/tv/ui/screens/account/AccountScreen.kt")
    private val accountViewModel = File("app/src/main/java/com/nexio/tv/ui/screens/account/AccountViewModel.kt")
    private val syncRepository = File("app/src/main/java/com/nexio/tv/domain/repository/SyncRepository.kt")
    private val syncRepositoryImpl = File("app/src/main/java/com/nexio/tv/data/repository/SyncRepositoryImpl.kt")

    @Test
    fun `linked devices screen no longer exposes unlink action`() {
        val screenSource = accountScreen.readText()
        val viewModelSource = accountViewModel.readText()

        assertFalse(screenSource.contains("onUnlink = { viewModel.unlinkDevice(it) }"))
        assertFalse(screenSource.contains("Icons.Default.LinkOff"))
        assertFalse(viewModelSource.contains("fun unlinkDevice("))
        assertTrue(screenSource.contains("account_linked_devices_info"))
    }

    @Test
    fun `sync repository no longer exposes legacy unlink semantics`() {
        assertFalse(syncRepository.readText().contains("unlinkDevice("))
        assertFalse(syncRepositoryImpl.readText().contains("unlinkDevice("))
        assertFalse(syncRepositoryImpl.readText().contains("unlink_device"))
    }
}
