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
