package com.nexio.tv.core.anime.binary

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnimeIdMapBinaryReader"
private const val ASSET_PATH = "anime/nexio-anime-map-v1.bin"
private const val BINARY_FORMAT_VERSION = 1
private const val DIR_NAME = "anime-id-map"

@Singleton
class AnimeIdMapBinaryReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var state: State = State.Closed
    private val openLock = Any()

    internal sealed interface State {
        object Closed : State
        object Failed : State
        class Open(
            val parent: ByteBuffer,
            val header: ByteBuffer,
            val indexTable: ByteBuffer,
            val indexRegion: ByteBuffer,
            val multiListPool: ByteBuffer,
            val records: ByteBuffer,
            val stringPool: ByteBuffer,
        ) : State
    }

    fun ensureOpen() {
        if (state !== State.Closed) return
        synchronized(openLock) {
            if (state !== State.Closed) return
            state = openInternal()
        }
    }

    fun isOpen(): Boolean = state is State.Open
    fun isFailed(): Boolean = state === State.Failed

    private fun openInternal(): State {
        val file = ensureBinaryOnDisk() ?: return State.Failed
        return try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val full = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
                parseHeaderAndSlice(full).also {
                    Log.i(TAG, "open ok schema=$BINARY_FORMAT_VERSION sizeBytes=${channel.size()}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "header_invalid_or_unreadable", t)
            // Attempt one recopy in case the on-disk copy is corrupted.
            runCatching { file.delete() }
            val retry = ensureBinaryOnDisk() ?: return State.Failed
            try {
                FileChannel.open(retry.toPath(), StandardOpenOption.READ).use { channel ->
                    val full = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                        .order(ByteOrder.LITTLE_ENDIAN)
                    parseHeaderAndSlice(full)
                }
            } catch (t2: Throwable) {
                Log.w(TAG, "header_invalid_after_recopy", t2)
                State.Failed
            }
        }
    }

    private fun parseHeaderAndSlice(full: ByteBuffer): State {
        require(full.capacity() >= BinaryFormat.HEADER_SIZE) { "file shorter than header" }
        for (i in BinaryFormat.MAGIC_BYTES.indices) {
            require(full.get(i) == BinaryFormat.MAGIC_BYTES[i]) {
                "bad magic byte at $i: ${full.get(i)}"
            }
        }
        val schema = full.getInt(4)
        require(schema == BinaryFormat.SCHEMA_VERSION) { "unsupported schemaVersion=$schema" }
        val recordsOffset = full.getLong(20)
        val recordsLength = full.getLong(28)
        val indexTableOffset = full.getLong(36)
        val stringPoolOffset = full.getLong(44)
        val stringPoolLength = full.getLong(52)

        val header = slice(full, 0, BinaryFormat.HEADER_SIZE)
        val indexTable = slice(full, indexTableOffset.toInt(), BinaryFormat.INDEX_TABLE_SIZE)
        val indexRegionStart = (indexTableOffset + BinaryFormat.INDEX_TABLE_SIZE).toInt()
        val indexRegionEnd = recordsOffset.toInt()
        val indexRegion = slice(full, indexRegionStart, indexRegionEnd - indexRegionStart)
        val multiListPool = slice(full, indexRegionStart, indexRegionEnd - indexRegionStart)
        val records = slice(full, recordsOffset.toInt(), recordsLength.toInt())
        val stringPool = slice(full, stringPoolOffset.toInt(), stringPoolLength.toInt())
        return State.Open(full, header, indexTable, indexRegion, multiListPool, records, stringPool)
    }

    private fun slice(full: ByteBuffer, offset: Int, length: Int): ByteBuffer {
        val dup = full.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(offset)
        dup.limit(offset + length)
        return dup.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    fun containsKitsu(kitsuId: String): Boolean {
        val open = state as? State.Open ?: return false
        val key = kitsuId.toLongOrNull() ?: return false
        return findSingleEntry(open, IndexKind.BY_KITSU, key) >= 0
    }

    internal fun lookupSingle(kind: IndexKind, value: String): String? {
        val open = state as? State.Open ?: return null
        val key = value.toLongOrNull() ?: return null
        val entryIndex = findSingleEntry(open, kind, key)
        if (entryIndex < 0) return null
        val descriptor = readDescriptor(open, kind.slot)
        val entryStart = descriptor.offset.toInt() + entryIndex * BinaryFormat.STRIDE_U64_SINGLE
        val recordOffset = open.parent.getInt(entryStart + 8)
        // For single-value indexes the value IS the kitsu numeric ID of the
        // referenced record — decode the kitsu varint from the record's body.
        return readRecordKitsuId(open, recordOffset)
    }

    internal fun lookupMultiFirst(kind: IndexKind, value: String): String? {
        val offsets = recordOffsetsForMultiKey(kind, value)
        if (offsets.isEmpty()) return null
        val open = state as State.Open
        return readRecordKitsuId(open, offsets[0])
    }

    internal fun recordOffsetsForMultiKey(kind: IndexKind, value: String): IntArray {
        val open = state as? State.Open ?: return IntArray(0)
        val key = value.toLongOrNull() ?: return IntArray(0)
        val d = readDescriptor(open, kind.slot)
        require(d.kind == BinaryFormat.KIND_U64_MULTI) {
            "expected KIND_U64_MULTI for $kind, got ${d.kind}"
        }
        val entryIndex = binarySearchU64(open.parent, d.offset.toInt(), BinaryFormat.STRIDE_U64_MULTI, d.count.toInt(), key)
        if (entryIndex < 0) return IntArray(0)
        val entryStart = d.offset.toInt() + entryIndex * BinaryFormat.STRIDE_U64_MULTI
        val listOffset = open.parent.getInt(entryStart + 8)
        val listLen = open.parent.getInt(entryStart + 12)
        val result = IntArray(listLen)
        for (i in 0 until listLen) {
            result[i] = open.parent.getInt(listOffset + i * 4)
        }
        return result
    }

    private data class Descriptor(val kind: Int, val stride: Int, val offset: Long, val count: Long)

    private fun readDescriptor(open: State.Open, slot: Int): Descriptor {
        val base = slot * BinaryFormat.INDEX_DESCRIPTOR_SIZE
        val k = open.indexTable.getInt(base)
        val stride = open.indexTable.getInt(base + 4)
        val offset = open.indexTable.getLong(base + 8)
        val count = open.indexTable.getLong(base + 16)
        return Descriptor(k, stride, offset, count)
    }

    private fun findSingleEntry(open: State.Open, kind: IndexKind, key: Long): Int {
        val d = readDescriptor(open, kind.slot)
        require(d.kind == BinaryFormat.KIND_U64_SINGLE) {
            "expected KIND_U64_SINGLE for $kind, got ${d.kind}"
        }
        return binarySearchU64(open.parent, d.offset.toInt(), BinaryFormat.STRIDE_U64_SINGLE, d.count.toInt(), key)
    }

    private fun binarySearchU64(region: ByteBuffer, baseOffset: Int, stride: Int, count: Int, key: Long): Int {
        var lo = 0; var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midKey = region.getLong(baseOffset + mid * stride)
            when {
                midKey < key -> lo = mid + 1
                midKey > key -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun readRecordKitsuId(open: State.Open, recordOffset: Int): String? {
        val records = open.records
        if (records.get(recordOffset).toInt() and 0xFF != BinaryFormat.RECORD_KIND_IDENTITY.toInt() and 0xFF) {
            return null
        }
        // skip recordKind(1) + presence(1) + presence2(1)
        val r = LongRef()
        VarintReader.readULong(records, recordOffset + 3, r)
        return r.value.toString()
    }

    private fun ensureBinaryOnDisk(): File? {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "fmt$BINARY_FORMAT_VERSION.bin")
        if (target.exists() && target.length() > 0) return target
        return runCatching {
            val tmp = File(dir, "fmt$BINARY_FORMAT_VERSION.bin.tmp")
            context.assets.open(ASSET_PATH).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            }
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            dir.listFiles { f -> f.name.startsWith("fmt") && f != target }?.forEach { it.delete() }
            target
        }.onFailure { Log.w(TAG, "copy_failed", it) }.getOrNull()
    }
}
