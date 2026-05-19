package com.nexio.tv.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SimklBootSyncSingleFlightTest {
    @Test
    fun `simkl boot sync single flight prevents duplicate activity calls`() = runTest {
        val singleFlight = SimklAccountSyncSingleFlight()
        val calls = AtomicInteger(0)

        val results = List(2) {
            async {
                singleFlight.run("simkl:last_activities:1") {
                    calls.incrementAndGet()
                    delay(10)
                    "ok"
                }
            }
        }.awaitAll()

        assertEquals(listOf("ok", "ok"), results)
        assertEquals(1, calls.get())
    }

    @Test
    fun `simkl services are wired through shared single flight coordinator`() {
        val progress = File("app/src/main/java/com/nexio/tv/data/repository/SimklProgressService.kt").readText()
        val library = File("app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt").readText()

        assertTrue(progress.contains("SimklAccountSyncSingleFlight"))
        assertTrue(progress.contains("accountSingleFlight.run(\"simkl:raw:"))
        assertTrue(library.contains("SimklAccountSyncSingleFlight"))
        assertTrue(library.contains("accountSingleFlight.run(\"simkl:last_activities:"))
    }
}
