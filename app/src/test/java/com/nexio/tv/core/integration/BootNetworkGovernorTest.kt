package com.nexio.tv.core.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootNetworkGovernorTest {
    @Test
    fun `boot governor blocks rating provider network during first paint`() {
        val governor = BootNetworkGovernor()
        governor.beginBootWindow()

        assertFalse(governor.allow(IntegrationProvider.MDBLIST, IntegrationWorkClass.BACKGROUND_HYDRATION))
        assertFalse(governor.allow(IntegrationProvider.CUSTOM_IMDB, IntegrationWorkClass.BACKGROUND_HYDRATION))
    }

    @Test
    fun `boot governor allows user visible detail call during boot window`() {
        val governor = BootNetworkGovernor()
        governor.beginBootWindow()

        assertTrue(governor.allow(IntegrationProvider.MDBLIST, IntegrationWorkClass.USER_VISIBLE))
    }

    @Test
    fun `boot governor ends budget after boot window`() {
        val governor = BootNetworkGovernor()
        governor.beginBootWindow()
        governor.endBootWindow()

        assertTrue(governor.allow(IntegrationProvider.MDBLIST, IntegrationWorkClass.BACKGROUND_HYDRATION))
    }

    @Test
    fun `home view model opens and closes boot network window`() {
        val source = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()

        assertTrue(source.contains("private val bootNetworkGovernor: BootNetworkGovernor"))
        assertTrue(source.contains("bootNetworkGovernor.beginBootWindow()"))
        assertTrue(source.contains("delay(BOOT_NETWORK_WINDOW_MS)"))
        assertTrue(source.contains("bootNetworkGovernor.endBootWindow()"))
    }
}
