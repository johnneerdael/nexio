package com.nexio.tv.ui.screens.player

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages two executor lanes for download work: urgent (playback frontier) and prefetch
 * (speculative ahead-of-playhead). The urgent lane has reserved capacity and is never
 * blocked by prefetch admission control. The prefetch lane is cancellable and subject to
 * back-pressure from the urgent queue depth.
 */
internal class DualLaneScheduler(
    urgentWorkers: Int = 2,
    prefetchWorkers: Int = 1
) {
    private var urgentExecutor: ExecutorService = Executors.newFixedThreadPool(urgentWorkers)
    private var prefetchExecutor: ExecutorService = Executors.newFixedThreadPool(prefetchWorkers)

    private val prefetchFutures: ConcurrentLinkedDeque<Future<*>> = ConcurrentLinkedDeque()
    private val pendingUrgentTasks: AtomicInteger = AtomicInteger(0)

    val isShutdown: Boolean
        get() = urgentExecutor.isShutdown && prefetchExecutor.isShutdown

    val pendingUrgentCount: Int
        get() = pendingUrgentTasks.get()

    fun submitUrgent(range: LongRange, callback: (LongRange) -> Unit): Future<*> {
        pendingUrgentTasks.incrementAndGet()
        return urgentExecutor.submit {
            try {
                callback(range)
            } finally {
                pendingUrgentTasks.decrementAndGet()
            }
        }
    }

    fun submitPrefetch(range: LongRange, callback: (LongRange) -> Unit): Future<*>? {
        if (pendingUrgentTasks.get() > 0) return null
        val future = prefetchExecutor.submit {
            callback(range)
        }
        prefetchFutures.addLast(future)
        return future
    }

    fun cancelAllPrefetch() {
        val iter = prefetchFutures.iterator()
        while (iter.hasNext()) {
            iter.next().cancel(true)
            iter.remove()
        }
    }

    fun cancelAll() {
        cancelAllPrefetch()
        urgentExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
    }

    fun reconfigure(urgentWorkers: Int, prefetchWorkers: Int) {
        urgentExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
        prefetchFutures.clear()
        urgentExecutor = Executors.newFixedThreadPool(urgentWorkers)
        prefetchExecutor = Executors.newFixedThreadPool(prefetchWorkers)
    }
}
