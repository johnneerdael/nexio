package com.nexio.tv.instrumentation

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pooled record carried through the MPSC ring. Steady-state hot path performs
 * zero allocations once each producer thread has warmed its thread-local pool.
 *
 * Pool shape (spec §A.3 amendment A7):
 *  - per-thread `ArrayDeque<TraceRecord>` capped at 64
 *  - shared spill freelist capacity 1024
 */
class TraceRecord internal constructor() {
    @JvmField var family: EventFamily = EventFamily.TRACER
    @JvmField var type: String = ""
    @JvmField var tNanos: Long = 0L
    @JvmField var thread: String = ""

    /** Pre-formatted JSON key/value fragments for the payload, e.g. `,"k":"v"`. */
    @JvmField val payloadBuffer: StringBuilder = StringBuilder(128)

    fun resetPayload() {
        payloadBuffer.setLength(0)
    }

    fun recycle() {
        family = EventFamily.TRACER
        type = ""
        tNanos = 0L
        thread = ""
        resetPayload()
        TraceRecordPool.release(this)
    }

    companion object {
        @JvmStatic
        fun obtain(family: EventFamily, type: String): TraceRecord {
            val rec = TraceRecordPool.acquire()
            rec.family = family
            rec.type = type
            rec.tNanos = android.os.SystemClock.elapsedRealtimeNanos()
            rec.thread = Thread.currentThread().name
            rec.resetPayload()
            return rec
        }
    }
}

/**
 * Two-tier free pool for [TraceRecord]:
 *  1. Per-thread `ArrayDeque` (cap 64) — lock-free for the owning thread.
 *  2. Shared freelist (cap 1024) — used when a thread's local deque overflows
 *     on recycle or is empty on acquire.
 *
 * Once each producer thread has warmed its thread-local pool, both `acquire()`
 * and `release()` are zero-allocation. New `TraceRecord` instances are only
 * allocated when both tiers are empty.
 */
internal object TraceRecordPool {
    private const val THREAD_LOCAL_CAP = 64
    private const val SHARED_CAP = 1024

    private val sharedFreelist = ConcurrentLinkedQueue<TraceRecord>()
    private val sharedFreelistSize = AtomicInteger(0)

    private val local = object : ThreadLocal<ArrayDeque<TraceRecord>>() {
        override fun initialValue(): ArrayDeque<TraceRecord> = ArrayDeque(THREAD_LOCAL_CAP)
    }

    fun acquire(): TraceRecord {
        val deque = local.get()!!
        val tl = deque.removeLastOrNull()
        if (tl != null) return tl
        val shared = sharedFreelist.poll()
        if (shared != null) {
            sharedFreelistSize.decrementAndGet()
            return shared
        }
        return TraceRecord()
    }

    fun release(rec: TraceRecord) {
        val deque = local.get()!!
        if (deque.size < THREAD_LOCAL_CAP) {
            deque.addLast(rec)
            return
        }
        while (true) {
            val currentSharedSize = sharedFreelistSize.get()
            if (currentSharedSize >= SHARED_CAP) return
            if (!sharedFreelistSize.compareAndSet(currentSharedSize, currentSharedSize + 1)) continue
            sharedFreelist.offer(rec)
            return
        }
    }
}
