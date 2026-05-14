package com.nexio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
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

class HydratedHomeOverlayTypedStore(
    private val file: File,
    private val gson: Gson
) {
    data class StoredOverlayRecord(
        val schemaVersion: Int,
        val value: HydratedHomeOverlay
    )

    private data class SharedState(
        val lock: Any = Any(),
        var loaded: Boolean = false,
        val aliases: LinkedHashMap<String, String> = linkedMapOf(),
        val overlays: LinkedHashMap<String, StoredOverlayRecord> = linkedMapOf()
    )

    companion object {
        private const val STORE_SCHEMA_VERSION = 2
        private const val OVERLAY_SCHEMA_VERSION = 1
        private const val OVERLAY_PREFIX = "overlay::"

        private val states = ConcurrentHashMap<String, SharedState>()

        fun resetSharedStateForTest(file: File) {
            states.remove(stateKey(file))
        }

        private fun stateFor(file: File): SharedState =
            states.getOrPut(stateKey(file)) { SharedState() }

        private fun stateKey(file: File): String = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absolutePath
        }
    }

    private val state = stateFor(file)

    fun upsert(overlay: HydratedHomeOverlay, aliasKeys: Set<String>): Boolean = synchronized(state.lock) {
        val overlayKey = cleanKey(overlay.overlayKey) ?: return false
        if (overlayKey != overlay.overlayKey) return false
        if (!overlay.isValidFor(overlayKey)) return false
        ensureLoadedLocked()
        val candidateAliases = linkedMapOf<String, String>().also { it.putAll(state.aliases) }
        val candidateOverlays = linkedMapOf<String, StoredOverlayRecord>().also { it.putAll(state.overlays) }
        candidateOverlays[overlayKey] = StoredOverlayRecord(
            schemaVersion = OVERLAY_SCHEMA_VERSION,
            value = overlay
        )
        for (aliasKey in aliasKeys) {
            val cleanAlias = cleanKey(aliasKey) ?: continue
            candidateAliases[cleanAlias] = overlayKey
        }
        writeAndSwapLocked(candidateAliases, candidateOverlays)
    }

    fun putAlias(aliasKey: String, overlayKey: String): Boolean = synchronized(state.lock) {
        val cleanAlias = cleanKey(aliasKey) ?: return false
        val cleanOverlayKey = cleanKey(overlayKey) ?: return false
        ensureLoadedLocked()
        if (!state.overlays.containsKey(cleanOverlayKey)) return false
        val candidateAliases = linkedMapOf<String, String>().also { it.putAll(state.aliases) }
        val candidateOverlays = linkedMapOf<String, StoredOverlayRecord>().also { it.putAll(state.overlays) }
        candidateAliases[cleanAlias] = cleanOverlayKey
        writeAndSwapLocked(candidateAliases, candidateOverlays)
    }

    fun aliasOverlayKey(aliasKey: String): String? = synchronized(state.lock) {
        ensureLoadedLocked()
        state.aliases[aliasKey.trim()]
    }

    fun overlay(overlayKey: String): HydratedHomeOverlay? = synchronized(state.lock) {
        ensureLoadedLocked()
        state.overlays[overlayKey.trim()]?.value
    }

    fun aliasKeys(): Set<String> = synchronized(state.lock) {
        ensureLoadedLocked()
        state.aliases.keys.toSet()
    }

    fun overlayKeys(): Set<String> = synchronized(state.lock) {
        ensureLoadedLocked()
        state.overlays.keys.toSet()
    }

    fun removeAliases(aliasKeys: Collection<String>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val cleanAliasKeys = aliasKeys.mapNotNull(::cleanKey).toSet()
        if (cleanAliasKeys.isEmpty()) return true
        if (cleanAliasKeys.none { key -> state.aliases.containsKey(key) }) return true
        val candidateAliases = linkedMapOf<String, String>().also { it.putAll(state.aliases) }
        val candidateOverlays = linkedMapOf<String, StoredOverlayRecord>().also { it.putAll(state.overlays) }
        for (aliasKey in cleanAliasKeys) {
            candidateAliases.remove(aliasKey)
        }
        writeAndSwapLocked(candidateAliases, candidateOverlays)
    }

    fun clearAll(): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (state.aliases.isEmpty() && state.overlays.isEmpty()) return true
        writeAndSwapLocked(emptyMap(), emptyMap())
    }

    fun migrateFromV1File(v1File: File): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val migrated = try {
            readV1File(v1File)
        } catch (_: IOException) {
            return false
        } catch (_: JsonParseException) {
            return false
        } catch (_: IllegalStateException) {
            return false
        }
        if (migrated.aliases.isEmpty() && migrated.overlays.isEmpty()) return true

        val candidateAliases = linkedMapOf<String, String>().also { it.putAll(state.aliases) }
        val candidateOverlays = linkedMapOf<String, StoredOverlayRecord>().also { it.putAll(state.overlays) }
        var changed = false

        for ((overlayKey, overlay) in migrated.overlays) {
            if (candidateOverlays.containsKey(overlayKey)) continue
            candidateOverlays[overlay.overlayKey] = StoredOverlayRecord(
                schemaVersion = OVERLAY_SCHEMA_VERSION,
                value = overlay
            )
            changed = true
        }

        for ((aliasKey, overlayKey) in migrated.aliases) {
            if (candidateAliases.containsKey(aliasKey)) continue
            if (!candidateOverlays.containsKey(overlayKey)) continue
            candidateAliases[aliasKey] = overlayKey
            changed = true
        }

        if (!changed) return true
        writeAndSwapLocked(candidateAliases, candidateOverlays)
    }

    fun snapshotForTest(): Map<String, Any> = synchronized(state.lock) {
        ensureLoadedLocked()
        val snapshot = linkedMapOf<String, Any>()
        for ((aliasKey, overlayKey) in state.aliases) {
            snapshot[aliasKey] = overlayKey
        }
        for ((overlayKey, record) in state.overlays) {
            snapshot[overlayKey] = record
        }
        snapshot
    }

    private fun ensureLoadedLocked() {
        if (state.loaded) return
        state.aliases.clear()
        state.overlays.clear()
        if (file.exists()) {
            try {
                readFileLocked()
                pruneDanglingAliasesLocked()
            } catch (_: IOException) {
                state.aliases.clear()
                state.overlays.clear()
            } catch (_: JsonParseException) {
                state.aliases.clear()
                state.overlays.clear()
            } catch (_: IllegalStateException) {
                state.aliases.clear()
                state.overlays.clear()
            }
        }
        state.loaded = true
    }

    private fun readFileLocked() {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        return
                    }
                    var schemaVersion: Int? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "schemaVersion" -> {
                                schemaVersion = reader.nextIntOrNull()
                            }
                            "aliases" -> readAliasesLocked(reader)
                            "overlays" -> readOverlaysLocked(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (schemaVersion != STORE_SCHEMA_VERSION) {
                        state.aliases.clear()
                        state.overlays.clear()
                    }
                }
            }
        }
    }

    private data class V1Entries(
        val aliases: LinkedHashMap<String, String> = linkedMapOf(),
        val overlays: LinkedHashMap<String, HydratedHomeOverlay> = linkedMapOf()
    )

    private fun readV1File(v1File: File): V1Entries {
        val migrated = V1Entries()
        if (!v1File.exists()) return migrated
        FileInputStream(v1File).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        return migrated
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = cleanKey(reader.nextName())
                        if (key == null || reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        when {
                            key.startsWith(OVERLAY_PREFIX) -> {
                                val overlayKey = cleanKey(key.removePrefix(OVERLAY_PREFIX))
                                val overlay = if (overlayKey == null) {
                                    reader.skipValue()
                                    null
                                } else {
                                    readV1OverlayRecord(reader, overlayKey)
                                }
                                if (overlay != null) migrated.overlays[overlay.overlayKey] = overlay
                            }
                            key.startsWith("alias::") -> {
                                val overlayKey = readV1AliasRecord(reader)
                                if (overlayKey != null) migrated.aliases[key] = overlayKey
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
        }
        return migrated
    }

    private fun readAliasesLocked(reader: JsonReader) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val aliasKey = cleanKey(reader.nextName())
            if (reader.peek() != JsonToken.STRING) {
                reader.skipValue()
                continue
            }
            val overlayKey = cleanKey(reader.nextString())
            if (aliasKey != null && overlayKey != null) {
                state.aliases[aliasKey] = overlayKey
            }
        }
        reader.endObject()
    }

    private fun readOverlaysLocked(reader: JsonReader) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val overlayKey = cleanKey(reader.nextName())
            if (overlayKey == null || reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            val record = readOverlayRecord(reader, overlayKey)
            if (record != null) {
                state.overlays[overlayKey] = record
            }
        }
        reader.endObject()
    }

    private fun readOverlayRecord(reader: JsonReader, expectedOverlayKey: String): StoredOverlayRecord? {
        var schemaVersion: Int? = null
        var overlay: HydratedHomeOverlay? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schemaVersion" -> {
                    schemaVersion = reader.nextIntOrNull()
                }
                "value" -> {
                    overlay = readOverlayValue(reader)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (schemaVersion != OVERLAY_SCHEMA_VERSION) return null
        val value = overlay ?: return null
        if (!value.isValidFor(expectedOverlayKey)) return null
        return StoredOverlayRecord(schemaVersion = schemaVersion, value = value)
    }

    private fun readOverlayValue(reader: JsonReader): HydratedHomeOverlay? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        return try {
            gson.fromJson<HydratedHomeOverlay>(reader, HydratedHomeOverlay::class.java)
                ?.normalizeDefaults()
        } catch (_: JsonParseException) {
            null
        } catch (_: JsonIOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readV1OverlayRecord(reader: JsonReader, expectedOverlayKey: String): HydratedHomeOverlay? {
        var schemaVersion: Int? = null
        var overlay: HydratedHomeOverlay? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schemaVersion" -> schemaVersion = reader.nextIntOrNull()
                "value" -> overlay = readOverlayValue(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (schemaVersion != OVERLAY_SCHEMA_VERSION) return null
        val value = overlay ?: return null
        if (!value.isValidFor(expectedOverlayKey)) return null
        return value
    }

    private fun readV1AliasRecord(reader: JsonReader): String? {
        var overlayKey: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "overlayKey" -> overlayKey = if (reader.peek() == JsonToken.STRING) {
                    cleanKey(reader.nextString())
                } else {
                    reader.skipValue()
                    null
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return overlayKey
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun HydratedHomeOverlay.normalizeDefaults(): HydratedHomeOverlay {
        val needsSnapshot = stableIdsSnapshot == null
        val needsSignature = settingsSignature == null
        if (!needsSnapshot && !needsSignature) return this
        return copy(
            stableIdsSnapshot = if (needsSnapshot) ProviderIds() else stableIdsSnapshot,
            settingsSignature = if (needsSignature) "" else settingsSignature
        )
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun HydratedHomeOverlay.isValidFor(expectedOverlayKey: String): Boolean {
        return try {
            if (this.overlayKey != expectedOverlayKey) return false
            if (this.overlayKey.trim().isEmpty()) return false
            if (this.itemKey == null) return false
            if (this.itemKey.trim().isEmpty()) return false
            if (this.canonicalProvider == null) return false
            if (this.canonicalId.trim().isEmpty()) return false
            if (this.contentType == null) return false
            if (this.languageTag.trim().isEmpty()) return false
            if (this.fields == null) return false
            if (this.fieldTrace == null) return false
            if (this.state == null) return false
            if (this.stableIdsSnapshot == null) return false
            if (this.settingsSignature == null) return false
            if (this.displayHash != this.fields.hydratedHomeDisplayHash()) return false
            if (hydratedHomeOverlayKey(
                    canonicalProvider = this.canonicalProvider,
                    canonicalId = this.canonicalId,
                    contentType = this.contentType,
                    languageTag = this.languageTag,
                    policyVersion = this.policyVersion
                ) != expectedOverlayKey
            ) {
                return false
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun pruneDanglingAliasesLocked() {
        val iterator = state.aliases.iterator()
        while (iterator.hasNext()) {
            if (!state.overlays.containsKey(iterator.next().value)) {
                iterator.remove()
            }
        }
    }

    private fun writeAndSwapLocked(
        aliases: Map<String, String>,
        overlays: Map<String, StoredOverlayRecord>
    ): Boolean {
        return if (writeLocked(aliases, overlays)) {
            state.aliases.clear()
            state.aliases.putAll(aliases)
            state.overlays.clear()
            state.overlays.putAll(overlays)
            state.loaded = true
            true
        } else {
            false
        }
    }

    private fun writeLocked(
        aliases: Map<String, String>,
        overlays: Map<String, StoredOverlayRecord>
    ): Boolean {
        var temp: File? = null
        return try {
            file.parentFile?.mkdirs()
            temp = tempFile()
            FileOutputStream(temp).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(STORE_SCHEMA_VERSION)
                        writer.name("aliases")
                        writer.beginObject()
                        for ((aliasKey, overlayKey) in aliases) {
                            writer.name(aliasKey).value(overlayKey)
                        }
                        writer.endObject()
                        writer.name("overlays")
                        writer.beginObject()
                        for ((overlayKey, record) in overlays) {
                            writer.name(overlayKey)
                            writer.beginObject()
                            writer.name("schemaVersion").value(record.schemaVersion)
                            writer.name("value")
                            gson.toJson(record.value, HydratedHomeOverlay::class.java, writer)
                            writer.endObject()
                        }
                        writer.endObject()
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

    private fun tempFile(): File {
        val parent = file.parentFile
        return if (parent != null) {
            File.createTempFile("${file.name}.", ".tmp", parent)
        } else {
            File.createTempFile("${file.name}.", ".tmp")
        }
    }

    private fun cleanKey(key: String): String? =
        key.trim().takeIf { it.isNotEmpty() }

    private fun JsonReader.nextIntOrNull(): Int? {
        return when (peek()) {
            JsonToken.NUMBER,
            JsonToken.STRING -> nextString().toIntOrNull()
            else -> {
                skipValue()
                null
            }
        }
    }
}
