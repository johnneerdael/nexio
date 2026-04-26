package com.nexio.tv.data.remote

import android.util.Log
import com.nexio.tv.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coroutine-based serial request gate for all Simkl API calls.
 *
 * Enforces a minimum of [MIN_INTERVAL_MS] between any two Simkl HTTP requests,
 * using [Mutex] + [delay] instead of Thread.sleep. Mirrors [TraktRequestGate].
 *
 * All Simkl API traffic flows through [SimklAuthService.executeAuthOwnerRequest],
 * which acquires this gate before dispatching each request.
 */
@Singleton
class SimklRequestGate @Inject constructor() {

    companion object {
        private const val TAG = "SimklRequestGate"
        /** Minimum milliseconds between request starts. */
        const val MIN_INTERVAL_MS = 500L
    }

    private val mutex = Mutex()

    @Volatile
    private var lastRequestAtMs = 0L

    /**
     * Acquires the gate, waits for the minimum interval since the last request,
     * then executes [block]. The mutex is held for the full duration so requests
     * are strictly serial.
     */
    suspend fun <T> acquire(block: suspend () -> T): T {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestAtMs
            if (lastRequestAtMs > 0L && elapsed < MIN_INTERVAL_MS) {
                val waitMs = MIN_INTERVAL_MS - elapsed
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "gate: waiting ${waitMs}ms before next request")
                }
                delay(waitMs)
            }
            lastRequestAtMs = System.currentTimeMillis()
            return block()
        }
    }
}
