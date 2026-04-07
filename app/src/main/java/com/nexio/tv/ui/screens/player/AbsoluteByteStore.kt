package com.nexio.tv.ui.screens.player

/**
 * Absolute-offset byte store abstraction. Producers write bytes at absolute file offsets;
 * readers copy contiguous bytes out. The production implementation wraps
 * [PagedFrontierBuffer]; the interface exists so tests can swap in a lightweight in-memory
 * fake (see `SequentialReadCursorTest.FakeStore`) and so the parallel-transport stack stays
 * agnostic to the underlying page layout.
 */
internal interface AbsoluteByteStore {
    val frontier: Long

    fun writeAt(absoluteOffset: Long, data: ByteArray, dataOffset: Int, length: Int)

    /**
     * Atomically publishes a fully-fetched prefetch chunk. The entire [length] bytes of
     * [chunk] starting at [absoluteStart] must be visible to readers in one atomic step —
     * no partially-filled chunk state may be observable. Used exclusively by the prefetch
     * lane; the urgent lane continues to use [writeAt].
     */
    fun publishCompleteChunk(absoluteStart: Long, chunk: ByteArray, length: Int)

    fun read(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int
    fun readableContiguousBytesFrom(position: Long): Long
    fun setTotalLength(length: Long)
    fun setBasePosition(position: Long)
    fun evictBefore(position: Long)
    fun reset()
}

/** 1:1 adapter over [PagedFrontierBuffer]. */
internal class PagedFrontierByteStore(
    private val buffer: PagedFrontierBuffer = PagedFrontierBuffer()
) : AbsoluteByteStore {
    override val frontier: Long
        get() = buffer.frontier

    override fun writeAt(absoluteOffset: Long, data: ByteArray, dataOffset: Int, length: Int) {
        buffer.onBytesWritten(absoluteOffset, data, dataOffset, length)
    }

    override fun publishCompleteChunk(absoluteStart: Long, chunk: ByteArray, length: Int) {
        buffer.publishCompleteChunk(absoluteStart, chunk, length)
    }

    override fun read(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int =
        buffer.read(position, dest, destOffset, length)

    override fun readableContiguousBytesFrom(position: Long): Long =
        buffer.readableContiguousBytesFrom(position)

    override fun setTotalLength(length: Long) {
        buffer.setTotalLength(length)
    }

    override fun setBasePosition(position: Long) {
        buffer.setBasePosition(position)
    }

    override fun evictBefore(position: Long) {
        buffer.evictBefore(position)
    }

    override fun reset() {
        buffer.reset()
    }
}
