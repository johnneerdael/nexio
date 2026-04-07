package com.nexio.tv.ui.screens.player

import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * A page-level byte buffer that tracks which 128KB pages are complete via a BitSet.
 *
 * Allows [read] to serve bytes as soon as contiguous pages exist from the read position,
 * eliminating head-of-line blocking on full chunk boundaries.
 *
 * Pages are pooled via a [ConcurrentLinkedDeque] to reduce GC churn from large byte array
 * allocations. Thread safety is provided by synchronized blocks on page-level mutations.
 */
internal class PagedFrontierBuffer(
    val pageSize: Int = PAGE_SIZE
) {

    companion object {
        const val PAGE_SIZE = 128 * 1024 // 128KB
    }

    // Page index → page data
    private val pages = ConcurrentHashMap<Int, ByteArray>()

    // Which pages have been fully written
    private val completedPages = BitSet()

    // Recycled page buffers
    private val pagePool = ConcurrentLinkedDeque<ByteArray>()
    private val maxPoolSize: Int = 32

    // Per-page fill tracking. Tracks contiguous bytes from page start (lowWater) plus
    // one pending out-of-order range. Handles overlapping writes (bootstrap + download),
    // out-of-order writes (chunk N+1 before chunk N), and partial reads correctly.
    private class PageFill(var lowWater: Int = 0, var pendingStart: Int = -1, var pendingEnd: Int = -1)
    private val pageFills = ConcurrentHashMap<Int, PageFill>()

    // Total length, used so partial last-page can be marked complete
    private var totalLength: Long = -1L

    // Tracks the contiguous frontier in bytes (absolute position, starts at the basePosition
    // passed to setBasePosition, or 0 for zero-position opens).
    private var contiguousFrontierBytes: Long = 0L

    val frontier: Long
        get() = synchronized(this) { contiguousFrontierBytes }

    /**
     * Inform the buffer of the total content length so the last (partial) page can be
     * marked complete once all its bytes are written.
     */
    fun setTotalLength(length: Long) {
        synchronized(this) {
            require(totalLength < 0L || totalLength == length) {
                "setTotalLength contradicts a prior value: was=$totalLength new=$length"
            }
            totalLength = length
        }
    }

    /**
     * Set the base position for non-zero opens (e.g., seeks). The frontier starts
     * at this position instead of 0, so pages before it are not required to be filled.
     */
    fun setBasePosition(position: Long) {
        synchronized(this) {
            require(pages.isEmpty()) { "setBasePosition must be called before any writes" }
            contiguousFrontierBytes = position
            // If basePosition is not page-aligned, pre-initialize the first page's lowWater
            // to the in-page offset so writes that begin exactly at basePosition extend the
            // contiguous fill (rather than landing in the pending range and never completing).
            val firstPageIndex = (position / pageSize).toInt()
            val offsetInFirstPage = (position % pageSize).toInt()
            if (offsetInFirstPage > 0) {
                pageFills.getOrPut(firstPageIndex) { PageFill() }.lowWater = offsetInFirstPage
            }
        }
    }

    /**
     * Write [length] bytes from [data] (starting at [dataOffset]) into the buffer at
     * [absoluteOffset]. Marks pages as complete when all their bytes have been received,
     * then advances the contiguous frontier.
     */
    fun onBytesWritten(absoluteOffset: Long, data: ByteArray, dataOffset: Int, length: Int) {
        var remaining = length
        var srcOffset = dataOffset
        var currentAbsOffset = absoluteOffset

        while (remaining > 0) {
            val pageIndex = (currentAbsOffset / pageSize).toInt()
            val offsetInPage = (currentAbsOffset % pageSize).toInt()
            val spaceInPage = pageSize - offsetInPage
            val toCopy = minOf(remaining, spaceInPage)

            synchronized(this) {
                val pageBuf = pages.getOrPut(pageIndex) { acquirePage() }
                System.arraycopy(data, srcOffset, pageBuf, offsetInPage, toCopy)

                val fill = pageFills.getOrPut(pageIndex) { PageFill() }
                val writeEnd = offsetInPage + toCopy
                if (offsetInPage <= fill.lowWater) {
                    fill.lowWater = maxOf(fill.lowWater, writeEnd)
                    if (fill.pendingStart >= 0 && fill.lowWater >= fill.pendingStart) {
                        fill.lowWater = maxOf(fill.lowWater, fill.pendingEnd)
                        fill.pendingStart = -1
                        fill.pendingEnd = -1
                    }
                } else {
                    if (fill.pendingStart < 0) {
                        fill.pendingStart = offsetInPage
                        fill.pendingEnd = writeEnd
                    } else {
                        fill.pendingStart = minOf(fill.pendingStart, offsetInPage)
                        fill.pendingEnd = maxOf(fill.pendingEnd, writeEnd)
                    }
                }

                val expectedPageBytes = expectedBytesForPage(pageIndex)
                if (fill.lowWater >= expectedPageBytes) {
                    completedPages.set(pageIndex)
                    advanceFrontier()
                }
            }

            currentAbsOffset += toCopy
            srcOffset += toCopy
            remaining -= toCopy
        }
    }

    /**
     * Returns the number of contiguous readable bytes starting from [position].
     * Only counts bytes within pages that are complete and contiguous from [position].
     */
    fun readableContiguousBytesFrom(position: Long): Long {
        synchronized(this) {
            if (contiguousFrontierBytes <= position) return 0L
            return contiguousFrontierBytes - position
        }
    }

    /**
     * Copies up to [length] bytes from completed contiguous pages into [dest] starting
     * at [destOffset]. Returns the number of bytes actually copied, or 0 if no contiguous
     * data is available at [position].
     */
    fun read(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        synchronized(this) {
            if (contiguousFrontierBytes <= position) return 0

            var totalCopied = 0
            var currentPos = position
            var destPos = destOffset

            while (totalCopied < length) {
                val pageIndex = (currentPos / pageSize).toInt()
                if (!completedPages[pageIndex]) break
                if (currentPos >= contiguousFrontierBytes) break

                val pageBuf = pages[pageIndex] ?: break
                val offsetInPage = (currentPos % pageSize).toInt()
                val availableInPage = minOf(
                    pageBuf.size - offsetInPage,
                    (contiguousFrontierBytes - currentPos).toInt()
                )
                val toCopy = minOf(length - totalCopied, availableInPage)
                if (toCopy <= 0) break

                System.arraycopy(pageBuf, offsetInPage, dest, destPos, toCopy)
                totalCopied += toCopy
                currentPos += toCopy
                destPos += toCopy
            }

            return totalCopied
        }
    }

    /**
     * Releases all pages strictly before [position] back to the pool.
     */
    fun evictBefore(position: Long) {
        val evictBeforePageIndex = (position / pageSize).toInt()
        val iter = pages.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < evictBeforePageIndex) {
                iter.remove()
                releasePage(entry.value)
                synchronized(this) {
                    pageFills.remove(entry.key)
                }
            }
        }
    }

    /**
     * Clears all pages and resets the frontier to 0. The pool is retained for reuse.
     */
    fun reset() {
        synchronized(this) {
            pages.values.forEach { releasePage(it) }
            pages.clear()
            completedPages.clear()
            pageFills.clear()
            contiguousFrontierBytes = 0L
            totalLength = -1L
        }
    }

    // Advance contiguousFrontierBytes as far as completed contiguous pages allow.
    // Must be called under synchronized(this).
    private fun advanceFrontier() {
        while (true) {
            val nextPageIndex = (contiguousFrontierBytes / pageSize).toInt()
            if (!completedPages[nextPageIndex]) break
            val pageEnd = (nextPageIndex.toLong() + 1L) * pageSize
            val newFrontier = if (totalLength in 0..pageEnd) totalLength else pageEnd
            if (newFrontier <= contiguousFrontierBytes) break
            contiguousFrontierBytes = newFrontier
        }
    }

    // Returns how many bytes we expect for a given page. For all pages except the last
    // this is pageSize. For the last page it is the remainder, if totalLength is known.
    private fun expectedBytesForPage(pageIndex: Int): Int {
        val total = totalLength
        if (total < 0L) return pageSize
        val pageStart = pageIndex.toLong() * pageSize
        val remaining = total - pageStart
        return if (remaining <= 0L) pageSize else minOf(remaining, pageSize.toLong()).toInt()
    }

    private fun acquirePage(): ByteArray = pagePool.pollLast() ?: ByteArray(pageSize)

    private fun releasePage(buf: ByteArray) {
        if (pagePool.size < maxPoolSize) {
            pagePool.offerLast(buf)
        }
    }
}
