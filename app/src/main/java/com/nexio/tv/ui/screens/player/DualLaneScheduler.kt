package com.nexio.tv.ui.screens.player

import com.nexio.tv.instrumentation.EventFamily
import com.nexio.tv.instrumentation.PayloadBuilder
import com.nexio.tv.instrumentation.PlaybackTracer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns a per-task scratch buffer and the bookkeeping needed for preempt/resume.
 *
 * When an urgent task arrives while a prefetch task is running the scheduler sets
 * [preempted] = true and cancels the future via Future.cancel(true). The prefetch
 * worker detects the flag, throws [PreemptedException], and exits without touching
 * retry counters. The handle (with [scratch] and [totalRead] intact) is pushed onto
 * [DualLaneScheduler.parkedPrefetch] and re-submitted once urgent work drains.
 *
 * Byte-identical resume is guaranteed because the resumed attempt issues a fresh HTTP
 * Range request at effectiveStart + [totalRead], which returns exactly the bytes the
 * original (interrupted) attempt would have produced from that offset.
 */
internal class PrefetchTaskHandle(
    val range: LongRange,
    val chunkSize: Long,
    val scratch: ByteArray,
    var totalRead: Int = 0,
    val preempted: AtomicBoolean = AtomicBoolean(false),
    /** Stored by [DualLaneScheduler.submitPrefetch] so [onUrgentDrained] can re-submit. */
    internal var resumeCallback: ((PrefetchTaskHandle) -> Unit)? = null
)

/** Thrown by [SharedParallelTransportManager.downloadRangeIntoScratch] when the handle is preempted. */
internal class PreemptedException : Exception("Prefetch preempted by urgent task")

/**
 * Manages two executor lanes for download work: urgent (playback frontier) and prefetch
 * (speculative ahead-of-playhead). The urgent lane has reserved capacity and is never
 * blocked by prefetch admission control.
 *
 * ## Preemption contract
 * When [submitUrgent] is called while a prefetch task is in-flight, the scheduler
 * preempts the currently-running prefetch task: sets [PrefetchTaskHandle.preempted],
 * cancels its Future (which triggers InterruptedIOException in OkHttp), and parks the
 * handle on [parkedPrefetch]. When [pendingUrgentTasks] returns to 0 after an urgent
 * task finishes, [onUrgentDrained] re-submits all parked handles so the prefetch
 * resumes from where it left off — byte-identical to an uninterrupted run.
 *
 * [submitPrefetch] never returns null; it always accepts the task (I4, I5 in Phase 4 memo).
 */
internal class DualLaneScheduler(
    urgentWorkers: Int = 2,
    prefetchWorkers: Int = 1
) {
    private var urgentExecutor: ExecutorService = Executors.newFixedThreadPool(urgentWorkers)
    private var prefetchExecutor: ExecutorService = Executors.newFixedThreadPool(prefetchWorkers)

    private val prefetchFutures: ConcurrentLinkedDeque<Future<*>> = ConcurrentLinkedDeque()
    private val pendingUrgentTasks: AtomicInteger = AtomicInteger(0)

    /** Running count of prefetch tasks preempted by an urgent task. */
    private val preemptCount = AtomicLong(0)

    /** Parked prefetch handles waiting to resume after urgent work drains. */
    private val parkedPrefetch: ArrayDeque<PrefetchTaskHandle> = ArrayDeque()

    /** The future for the currently-running prefetch task (null if none). */
    private var currentPrefetchFuture: Future<*>? = null

    /** The handle for the currently-running prefetch task (null if none). */
    private var currentPrefetchHandle: PrefetchTaskHandle? = null

    val isShutdown: Boolean
        get() = urgentExecutor.isShutdown && prefetchExecutor.isShutdown

    val pendingUrgentCount: Int
        get() = pendingUrgentTasks.get()

    private inline fun emitLaneEvent(type: String, crossinline build: PayloadBuilder.() -> Unit = {}) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.RANGE, type) {
            build()
        }
    }

    fun submitUrgent(range: LongRange, callback: (LongRange) -> Unit): Future<*> {
        // Preempt any in-flight prefetch task before incrementing the counter so that
        // the prefetch worker exits quickly and the urgent task can acquire bandwidth.
        synchronized(this) {
            val handle = currentPrefetchHandle
            val future = currentPrefetchFuture
            if (handle != null && future != null && !future.isDone) {
                handle.preempted.set(true)
                future.cancel(true)
                preemptCount.incrementAndGet()
                parkedPrefetch.addLast(handle)
                emitLaneEvent("lane_prefetch_preempted") {
                    putLong("prefetchStart", handle.range.first)
                    putLong("prefetchEndExclusive", handle.range.last + 1L)
                    putInt("totalRead", handle.totalRead)
                    putLong("preemptCount", preemptCount.get())
                }
                currentPrefetchHandle = null
                currentPrefetchFuture = null
            }
        }
        pendingUrgentTasks.incrementAndGet()
        emitLaneEvent("lane_submit_urgent") {
            putLong("start", range.first)
            putLong("endExclusive", range.last + 1L)
            putInt("pendingUrgent", pendingUrgentTasks.get())
        }
        return urgentExecutor.submit {
            try {
                callback(range)
            } finally {
                val remaining = pendingUrgentTasks.decrementAndGet()
                emitLaneEvent("lane_urgent_done") {
                    putLong("start", range.first)
                    putLong("endExclusive", range.last + 1L)
                    putInt("pendingUrgent", remaining)
                }
                if (remaining == 0) {
                    onUrgentDrained()
                }
            }
        }
    }

    /**
     * Accepts a prefetch task unconditionally — never returns null.
     *
     * If urgent work is currently pending the handle is immediately parked; it will be
     * submitted to the prefetch executor once [onUrgentDrained] fires.
     *
     * @param range Byte range to fetch.
     * @param chunkSize Expected chunk size in bytes (used to size the scratch buffer).
     * @param callback Called with the [PrefetchTaskHandle]; the worker must check
     *   [PrefetchTaskHandle.preempted] and throw [PreemptedException] to exit early
     *   without incrementing retry counters.
     * @return A Future for the submitted task (or a cancelled stub if immediately parked).
     */
    fun submitPrefetch(
        range: LongRange,
        chunkSize: Long,
        callback: (PrefetchTaskHandle) -> Unit
    ): Future<*> {
        val expectedBytes = (range.last - range.first + 1).coerceAtLeast(0L).toInt()
        val handle = PrefetchTaskHandle(
            range = range,
            chunkSize = chunkSize,
            scratch = ByteArray(expectedBytes),
            resumeCallback = callback
        )
        PlayerTransportTelemetry.logThrottled("dls.prefetch", 1000L, mapOf(
            "chunkSize" to chunkSize,
            "parkedCount" to synchronized(this) { parkedPrefetch.size },
            "preemptCount" to preemptCount.get(),
            "urgentActive" to pendingUrgentTasks.get()
        ))
        return synchronized(this) {
            if (pendingUrgentTasks.get() > 0) {
                // Park immediately — re-submitted by onUrgentDrained.
                parkedPrefetch.addLast(handle)
                emitLaneEvent("lane_prefetch_parked") {
                    putLong("start", range.first)
                    putLong("endExclusive", range.last + 1L)
                    putLong("chunkSize", chunkSize)
                    putInt("pendingUrgent", pendingUrgentTasks.get())
                }
                // Return a cancelled stub future so the caller does not need to null-check.
                prefetchExecutor.submit { }.also { it.cancel(false) }
            } else {
                emitLaneEvent("lane_submit_prefetch") {
                    putLong("start", range.first)
                    putLong("endExclusive", range.last + 1L)
                    putLong("chunkSize", chunkSize)
                }
                submitHandleToExecutor(handle, callback)
            }
        }
    }

    /** Must be called under synchronized(this). */
    private fun submitHandleToExecutor(
        handle: PrefetchTaskHandle,
        callback: (PrefetchTaskHandle) -> Unit
    ): Future<*> {
        val future = prefetchExecutor.submit {
            try {
                callback(handle)
            } finally {
                synchronized(this@DualLaneScheduler) {
                    if (currentPrefetchHandle === handle) {
                        currentPrefetchHandle = null
                        currentPrefetchFuture = null
                    }
                }
            }
        }
        prefetchFutures.addLast(future)
        currentPrefetchHandle = handle
        currentPrefetchFuture = future
        return future
    }

    /**
     * Called when [pendingUrgentTasks] drops to 0. Re-submits all parked prefetch handles
     * so they resume from [PrefetchTaskHandle.totalRead] — byte-identical to an uninterrupted run.
     */
    private fun onUrgentDrained() {
        synchronized(this) {
            while (parkedPrefetch.isNotEmpty()) {
                val handle = parkedPrefetch.pollFirst() ?: break
                val cb = handle.resumeCallback ?: continue
                // Reset preempted flag so the worker runs normally on re-submission.
                handle.preempted.set(false)
                emitLaneEvent("lane_prefetch_resume") {
                    putLong("start", handle.range.first)
                    putLong("endExclusive", handle.range.last + 1L)
                    putLong("chunkSize", handle.chunkSize)
                    putInt("totalRead", handle.totalRead)
                }
                submitHandleToExecutor(handle, cb)
            }
        }
    }

    fun cancelAllPrefetch() {
        synchronized(this) {
            val iter = prefetchFutures.iterator()
            while (iter.hasNext()) {
                iter.next().cancel(true)
                iter.remove()
            }
            parkedPrefetch.clear()
            currentPrefetchHandle = null
            currentPrefetchFuture = null
            emitLaneEvent("lane_cancel_all_prefetch")
        }
    }

    fun cancelAll() {
        cancelAllPrefetch()
        emitLaneEvent("lane_cancel_all")
        urgentExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
    }

    fun reconfigure(urgentWorkers: Int, prefetchWorkers: Int) {
        synchronized(this) {
            urgentExecutor.shutdownNow()
            prefetchExecutor.shutdownNow()
            prefetchFutures.clear()
            parkedPrefetch.clear()
            currentPrefetchHandle = null
            currentPrefetchFuture = null
            urgentExecutor = Executors.newFixedThreadPool(urgentWorkers)
            prefetchExecutor = Executors.newFixedThreadPool(prefetchWorkers)
            emitLaneEvent("lane_reconfigure") {
                putInt("urgentWorkers", urgentWorkers)
                putInt("prefetchWorkers", prefetchWorkers)
            }
        }
    }
}
