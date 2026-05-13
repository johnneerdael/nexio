package com.nexio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class FileBackedJsonObjectStore(
    private val file: File
) {
    private data class SharedState(
        val lock: Any = Any(),
        var loaded: Boolean = false,
        val entries: LinkedHashMap<String, JsonObject> = linkedMapOf()
    )

    companion object {
        private val states = ConcurrentHashMap<String, SharedState>()

        private fun stateFor(file: File): SharedState {
            val key = stateKey(file)
            return states.getOrPut(key) { SharedState() }
        }

        private fun stateKey(file: File): String = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }

        internal fun resetSharedStateForTest(file: File) {
            states.remove(stateKey(file))
        }
    }

    private val gson = Gson()
    private val state = stateFor(file)

    fun get(key: String): JsonObject? = synchronized(state.lock) {
        ensureLoadedLocked()
        state.entries[key.trim()]?.deepCopy()
    }

    fun keys(): Set<String> = synchronized(state.lock) {
        ensureLoadedLocked()
        state.entries.keys.toSet()
    }

    fun entries(): Map<String, JsonObject> = synchronized(state.lock) {
        ensureLoadedLocked()
        deepCopyEntries(state.entries)
    }

    fun put(key: String, value: JsonObject): Boolean = synchronized(state.lock) {
        val cleanKey = cleanKey(key) ?: return false
        ensureLoadedLocked()
        val candidate = deepCopyEntries(state.entries)
        candidate[cleanKey] = value.deepCopy()
        writeAndSwapLocked(candidate)
    }

    fun putAll(values: Map<String, JsonObject>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val candidate = deepCopyEntries(state.entries)
        var changed = false
        for ((key, value) in values) {
            val cleanKey = cleanKey(key) ?: continue
            candidate[cleanKey] = value.deepCopy()
            changed = true
        }
        if (changed) writeAndSwapLocked(candidate) else true
    }

    fun remove(key: String): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (!state.entries.containsKey(key.trim())) return true
        val candidate = deepCopyEntries(state.entries)
        candidate.remove(key.trim())
        writeAndSwapLocked(candidate)
    }

    fun removeAll(keys: Collection<String>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val cleanKeys = keys.mapNotNull(::cleanKey).toSet()
        if (cleanKeys.isEmpty()) return true
        if (cleanKeys.none { key -> state.entries.containsKey(key) }) return true
        val candidate = deepCopyEntries(state.entries)
        for (key in cleanKeys) {
            candidate.remove(key)
        }
        writeAndSwapLocked(candidate)
    }

    fun clear(): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (state.entries.isEmpty()) return true
        writeAndSwapLocked(linkedMapOf())
    }

    fun replaceAll(values: Map<String, JsonObject>): Boolean = synchronized(state.lock) {
        val candidate = linkedMapOf<String, JsonObject>()
        for ((key, value) in values) {
            val cleanKey = cleanKey(key) ?: continue
            candidate[cleanKey] = value.deepCopy()
        }
        writeAndSwapLocked(candidate)
    }

    private fun ensureLoadedLocked() {
        if (state.loaded) return
        state.entries.clear()
        if (file.exists()) {
            try {
                readFileLocked()
            } catch (_: IOException) {
                state.entries.clear()
            } catch (_: JsonParseException) {
                state.entries.clear()
            }
        }
        state.loaded = true
    }

    private fun readFileLocked() {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    when (reader.peek()) {
                        JsonToken.NULL -> {
                            reader.nextNull()
                            return
                        }
                        JsonToken.BEGIN_OBJECT -> Unit
                        else -> return
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            continue
                        }
                        val value = gson.fromJson<JsonObject>(reader, JsonObject::class.java)
                        if (value != null) state.entries[key] = value
                    }
                    reader.endObject()
                }
            }
        }
    }

    private fun writeAndSwapLocked(candidate: LinkedHashMap<String, JsonObject>): Boolean {
        return if (writeLocked(candidate)) {
            state.entries.clear()
            state.entries.putAll(candidate)
            state.loaded = true
            true
        } else {
            false
        }
    }

    private fun writeLocked(candidate: Map<String, JsonObject>): Boolean {
        var temp: File? = null
        return try {
            file.parentFile?.mkdirs()
            temp = tempFile()
            FileOutputStream(temp).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        for ((key, value) in candidate) {
                            writer.name(key)
                            gson.toJson(value, JsonObject::class.java, writer)
                        }
                        writer.endObject()
                    }
                }
            }
            moveReplacing(temp, file)
            true
        } catch (_: IOException) {
            temp?.delete()
            false
        } catch (_: JsonIOException) {
            temp?.delete()
            false
        } catch (_: SecurityException) {
            temp?.delete()
            false
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun cleanKey(key: String): String? =
        key.trim().takeIf { it.isNotEmpty() }

    private fun tempFile(): File {
        val parent = file.parentFile
        return if (parent != null) {
            File.createTempFile("${file.name}.", ".tmp", parent)
        } else {
            File.createTempFile("${file.name}.", ".tmp")
        }
    }

    private fun deepCopyEntries(source: Map<String, JsonObject>): LinkedHashMap<String, JsonObject> {
        val copy = linkedMapOf<String, JsonObject>()
        for ((key, value) in source) {
            copy[key] = value.deepCopy()
        }
        return copy
    }

}
