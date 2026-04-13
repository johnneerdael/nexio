package com.nexio.tv.ui.screens.player.spool

internal class DiskSpoolReadAheadBuffer(
    private val session: DiskSpoolSession,
    capacityBytes: Long,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    workerName: String = "Nexio-disk-spool-read-ahead"
) {
    private data class Chunk(
        val start: Long,
        val bytes: ByteArray,
        val length: Int
    ) {
        val endExclusive: Long = start + length
    }

    private val lock = Object()
    private val capacity = capacityBytes.coerceIn(0L, MAX_CAPACITY_BYTES)
    private val chunks = ArrayDeque<Chunk>()
    private var bufferedBytes = 0L
    private var nextReadPosition = 0L
    private var generation = 0L
    private var released = false
    private var worker: Thread? = null
    private val workerName = workerName

    fun start(position: Long) {
        if (capacity <= 0L) return
        synchronized(lock) {
            if (released) return
            generation += 1L
            resetLocked(position)
            if (worker?.isAlive == true) {
                lock.notifyAll()
                return
            }
            worker = Thread(::runWorker, workerName).apply {
                isDaemon = true
                start()
            }
            lock.notifyAll()
        }
    }

    fun reset(position: Long) {
        synchronized(lock) {
            if (released) return
            generation += 1L
            resetLocked(position)
            lock.notifyAll()
        }
    }

    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (capacity <= 0L || length <= 0) return 0
        synchronized(lock) {
            if (released) return 0
            val chunk = chunks.firstOrNull { position >= it.start && position < it.endExclusive }
                ?: return 0
            val relative = (position - chunk.start).toInt()
            val bytesToCopy = minOf(length, chunk.length - relative)
            System.arraycopy(chunk.bytes, relative, target, offset, bytesToCopy)
            dropChunksBefore(position + bytesToCopy)
            lock.notifyAll()
            return bytesToCopy
        }
    }

    fun release() {
        val thread = synchronized(lock) {
            released = true
            generation += 1L
            chunks.clear()
            bufferedBytes = 0L
            lock.notifyAll()
            worker
        }
        thread?.interrupt()
    }

    internal fun awaitBufferedBytesForTesting(minBytes: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!released && bufferedBytes < minBytes) {
                val remainingMs = deadline - System.currentTimeMillis()
                if (remainingMs <= 0L) return false
                lock.wait(minOf(remainingMs, 50L))
            }
            return bufferedBytes >= minBytes
        }
    }

    private fun runWorker() {
        val scratch = ByteArray(chunkBytes.coerceAtLeast(1))
        while (true) {
            val (startPosition, readGeneration) = synchronized(lock) {
                while (!released && bufferedBytes >= capacity) {
                    lock.wait(50L)
                }
                if (released) return
                nextReadPosition to generation
            }

            val read = session.read(startPosition, scratch, 0, scratch.size)
            if (read <= 0) {
                if (Thread.currentThread().isInterrupted || session.isClosed()) return
                synchronized(lock) {
                    if (!released && generation == readGeneration && startPosition < session.windowStartBytes()) {
                        resetLocked(session.windowStartBytes())
                    }
                }
                Thread.sleep(NON_POSITIVE_READ_BACKOFF_MS)
                continue
            }

            synchronized(lock) {
                if (released) return
                if (generation != readGeneration || startPosition != nextReadPosition) {
                    lock.notifyAll()
                    return@synchronized
                }
                val bytes = scratch.copyOf(read)
                chunks += Chunk(start = startPosition, bytes = bytes, length = read)
                bufferedBytes += read.toLong()
                nextReadPosition = startPosition + read
                trimToCapacityLocked()
                lock.notifyAll()
            }
        }
    }

    private fun resetLocked(position: Long) {
        chunks.clear()
        bufferedBytes = 0L
        nextReadPosition = position.coerceAtLeast(0L)
    }

    private fun trimToCapacityLocked() {
        while (bufferedBytes > capacity && chunks.isNotEmpty()) {
            val removed = chunks.removeFirst()
            bufferedBytes -= removed.length
        }
    }

    private fun dropChunksBefore(position: Long) {
        while (chunks.isNotEmpty() && chunks.first().endExclusive <= position) {
            val removed = chunks.removeFirst()
            bufferedBytes -= removed.length
        }
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 1024 * 1024
        const val MAX_CAPACITY_BYTES = 256L * 1024L * 1024L
        const val NON_POSITIVE_READ_BACKOFF_MS = 50L
    }
}
