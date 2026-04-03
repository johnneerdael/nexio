package com.nexio.tv.data.repository.benchmark

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DebridBenchmarkExecutionGate @Inject constructor() {
    private val mutex = Mutex()
    private var running = false

    suspend fun tryAcquire(): Boolean = mutex.withLock {
        if (running) {
            false
        } else {
            running = true
            true
        }
    }

    suspend fun release() {
        mutex.withLock {
            running = false
        }
    }
}

