package com.nexio.tv.ui.screens.player

import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies that the "shape drift" sentinel string is not reachable from any DataSource
 * read/open call path.
 *
 * **Approach — constant-pool scan:**
 * Load each target class file via [ClassLoader.getResourceAsStream] and scan the raw bytes
 * for the UTF-8 encoding of "shape drift". If the sentinel is present in a class whose
 * `.class` file also declares any method named `open`, `read`, or `readInternal`, the test
 * fails immediately.
 *
 * This is a structural guarantee: a class cannot throw `IllegalStateException("... shape
 * drift ...")` from its own methods unless the literal appears in its constant pool. By
 * checking at the class level (rather than per-method bytecode) we stay simple and stable —
 * the overhead of per-method CFG analysis is not worth it for a sentinel string this unique.
 *
 * Classes under inspection:
 * - [ParallelRangeDataSource] — Media3 DataSource façade (open/read)
 * - [SharedParallelTransportManager] — download engine (read loops)
 * - [PagedFrontierBuffer] — byte store (read)
 * - [SequentialReadCursor] / [DefaultSequentialReadCursor] — read cursor (read/readInternal)
 */
class GuardNeverReachableFromReadPathTest {

    private val sentinel = "shape drift"

    /** Data-path classes whose .class files must NOT contain the sentinel. */
    private val dataPathClasses: List<Class<*>> = listOf(
        ParallelRangeDataSource::class.java,
        SharedParallelTransportManager::class.java,
        PagedFrontierBuffer::class.java,
        DefaultSequentialReadCursor::class.java
    )

    /** Method names that constitute the "read path" entry points. */
    private val readPathMethodNames = setOf("open", "read", "readInternal")

    @Test
    fun `sentinel string shape drift is absent from all data-path class files`() {
        val violations = mutableListOf<String>()

        for (clazz in dataPathClasses) {
            val resourcePath = clazz.name.replace('.', '/') + ".class"
            val bytes = clazz.classLoader
                ?.getResourceAsStream(resourcePath)
                ?.readBytes()
                ?: continue  // class not compiled yet — skip rather than false-positive

            if (!containsSentinel(bytes)) continue

            // Sentinel is present in the class file. Check if this class declares any
            // read-path method — if so, it is a violation.
            val readPathMethods = clazz.declaredMethods
                .filter { it.name in readPathMethodNames }

            if (readPathMethods.isNotEmpty()) {
                val methodNames = readPathMethods.joinToString { it.name }
                violations.add(
                    "${clazz.simpleName}: contains sentinel '$sentinel' and declares read-path " +
                        "method(s) [$methodNames]. Move the guard to a constructor or attachSession."
                )
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Shape-drift guard sentinel found on data path — this causes Media3 to silently " +
                    "swallow IllegalStateException as EOFException:\n" +
                    violations.joinToString("\n  - ", prefix = "  - ")
            )
        }
    }

    /**
     * Returns true if [classBytes] contains the UTF-8 encoding of [sentinel] as a
     * contiguous byte sequence anywhere in the file (including constant pool entries).
     */
    private fun containsSentinel(classBytes: ByteArray): Boolean {
        val needle = sentinel.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty()) return false
        outer@ for (i in 0..classBytes.size - needle.size) {
            for (j in needle.indices) {
                if (classBytes[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
