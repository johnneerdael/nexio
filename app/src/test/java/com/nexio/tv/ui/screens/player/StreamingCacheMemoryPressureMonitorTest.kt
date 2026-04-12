package com.nexio.tv.ui.screens.player

import android.content.ComponentCallbacks2
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamingCacheMemoryPressureMonitorTest {

    @Test
    fun onTrimMemoryRunningLowInvokesCallback() {
        val callbackCount = AtomicInteger(0)
        val monitor = StreamingCacheMemoryPressureMonitor(
            context = ApplicationProvider.getApplicationContext(),
            onMemoryPressure = { callbackCount.incrementAndGet() }
        )

        monitor.start()
        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        monitor.stop()

        assertEquals(1, callbackCount.get())
    }

    @Test
    fun onTrimMemoryUiHiddenDoesNotInvokeCallback() {
        val callbackCount = AtomicInteger(0)
        val monitor = StreamingCacheMemoryPressureMonitor(
            context = ApplicationProvider.getApplicationContext(),
            onMemoryPressure = { callbackCount.incrementAndGet() }
        )

        monitor.start()
        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        monitor.stop()

        assertEquals(0, callbackCount.get())
    }
}
