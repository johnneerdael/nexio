package com.nexio.tv.core.media

import com.google.gson.Gson
import com.google.gson.JsonIOException
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
import java.io.StringReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

internal class MediaClipTypedStore(
    private val file: File,
    private val gson: Gson
) {
    private data class SharedState(
        val lock: Any = Any(),
        var loaded: Boolean = false,
        val records: LinkedHashMap<String, StoredMediaClipRecord> = linkedMapOf()
    )

    private val state = stateFor(file)

    fun record(key: String): StoredMediaClipRecord? = synchronized(state.lock) {
        ensureLoadedLocked()
        state.records[key.trim()]
    }

    fun records(): List<StoredMediaClipRecord> = synchronized(state.lock) {
        ensureLoadedLocked()
        state.records.values.toList()
    }

    fun putAll(records: Collection<StoredMediaClipRecord>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val candidate = linkedMapOf<String, StoredMediaClipRecord>().also { it.putAll(state.records) }
        var changed = false
        for (record in records) {
            val cleanKey = cleanKey(record.key) ?: continue
            if (!record.isValidFor(cleanKey)) continue
            candidate[cleanKey] = record
            changed = true
        }
        if (!changed) true else writeAndSwapLocked(candidate)
    }

    fun migrateFromV1File(v1File: File): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        val migrated = try {
            readV1File(v1File)
        } catch (_: IOException) {
            return false
        } catch (_: JsonParseException) {
            return false
        } catch (_: JsonIOException) {
            return false
        } catch (_: IllegalStateException) {
            return false
        }
        if (migrated.isEmpty()) return true
        mergeMigratedRecordsLocked(migrated)
    }

    fun migrateLegacyEntries(entries: Map<String, String>): Boolean = synchronized(state.lock) {
        ensureLoadedLocked()
        if (entries.isEmpty()) return true
        val migrated = linkedMapOf<String, StoredMediaClipRecord>()
        for ((rawKey, rawJson) in entries) {
            val key = cleanKey(rawKey) ?: continue
            if (state.records.containsKey(key)) continue
            val record = readLegacyRecord(rawJson, key) ?: continue
            migrated[key] = record
        }
        if (migrated.isEmpty()) return true
        mergeMigratedRecordsLocked(migrated)
    }

    private fun mergeMigratedRecordsLocked(migrated: Map<String, StoredMediaClipRecord>): Boolean {
        val candidate = linkedMapOf<String, StoredMediaClipRecord>().also { it.putAll(state.records) }
        var changed = false
        for ((key, record) in migrated) {
            if (candidate.containsKey(key)) continue
            candidate[key] = record
            changed = true
        }
        if (!changed) return true
        return writeAndSwapLocked(candidate)
    }

    private fun ensureLoadedLocked() {
        if (state.loaded) return
        state.records.clear()
        if (file.exists()) {
            try {
                readFileLocked()
            } catch (_: IOException) {
                state.records.clear()
            } catch (_: JsonParseException) {
                state.records.clear()
            } catch (_: JsonIOException) {
                state.records.clear()
            } catch (_: IllegalStateException) {
                state.records.clear()
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
                            "schemaVersion" -> schemaVersion = reader.nextIntOrNull()
                            "records" -> readRecordsLocked(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (schemaVersion != STORE_SCHEMA_VERSION) {
                        state.records.clear()
                    }
                }
            }
        }
    }

    private fun readRecordsLocked(reader: JsonReader) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val key = cleanKey(reader.nextName())
            if (key == null || reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            val record = readRecordValue(reader, key)
            if (record != null) {
                state.records[key] = record
            }
        }
        reader.endObject()
    }

    private fun readV1File(v1File: File): LinkedHashMap<String, StoredMediaClipRecord> {
        val records = linkedMapOf<String, StoredMediaClipRecord>()
        if (!v1File.exists()) return records
        FileInputStream(v1File).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        return records
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = cleanKey(reader.nextName())
                        if (key == null || state.records.containsKey(key) || reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        val record = readRecordValue(reader, key)
                        if (record != null) records[key] = record
                    }
                    reader.endObject()
                }
            }
        }
        return records
    }

    private fun readRecordValue(reader: JsonReader, expectedKey: String): StoredMediaClipRecord? {
        return try {
            readRecordFields(reader)
                ?.toRecord()
                ?.takeIf { record -> record.isValidFor(expectedKey) }
        } catch (_: JsonParseException) {
            runCatching { reader.skipValue() }
            null
        } catch (_: JsonIOException) {
            runCatching { reader.skipValue() }
            null
        } catch (_: IllegalArgumentException) {
            runCatching { reader.skipValue() }
            null
        } catch (_: IllegalStateException) {
            runCatching { reader.skipValue() }
            null
        }
    }

    private data class RecordFields(
        var key: String? = null,
        var clipId: String? = null,
        var contentId: String? = null,
        var itemType: String? = null,
        var tmdbId: String? = null,
        var tvdbId: String? = null,
        var imdbId: String? = null,
        var kitsuId: String? = null,
        var provider: String? = null,
        var source: String? = null,
        var scopeKind: String? = null,
        var season: Int? = null,
        var episode: Int? = null,
        var clipType: String? = null,
        var title: String? = null,
        var language: String? = null,
        var site: String? = null,
        var externalVideoId: String? = null,
        var playbackKind: String? = null,
        var youtubeId: String? = null,
        var providerUrlHash: String? = null,
        var redactedUrl: String? = null,
        var confidence: String? = null,
        var fetchedAtMs: Long? = null,
        var expiresAtMs: Long? = null,
        var staleUntilMs: Long? = null,
        var sourceTrace: List<String>? = null,
        var invalid: Boolean = false
    ) {
        fun toRecord(): StoredMediaClipRecord? {
            if (invalid) return null
            return StoredMediaClipRecord(
                key = key ?: return null,
                clipId = clipId ?: return null,
                contentId = contentId ?: return null,
                itemType = itemType,
                tmdbId = tmdbId,
                tvdbId = tvdbId,
                imdbId = imdbId,
                kitsuId = kitsuId,
                provider = provider ?: return null,
                source = source ?: return null,
                scopeKind = scopeKind ?: return null,
                season = season,
                episode = episode,
                clipType = clipType ?: return null,
                title = title,
                language = language,
                site = site ?: return null,
                externalVideoId = externalVideoId,
                playbackKind = playbackKind,
                youtubeId = youtubeId,
                providerUrlHash = providerUrlHash,
                redactedUrl = redactedUrl,
                confidence = confidence ?: return null,
                fetchedAtMs = fetchedAtMs ?: return null,
                expiresAtMs = expiresAtMs ?: return null,
                staleUntilMs = staleUntilMs ?: return null,
                sourceTrace = sourceTrace ?: return null
            )
        }
    }

    private fun readRecordFields(reader: JsonReader): RecordFields? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        val fields = RecordFields()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "key" -> fields.key = reader.nextStringOrNull(fields)
                "clipId" -> fields.clipId = reader.nextStringOrNull(fields)
                "contentId" -> fields.contentId = reader.nextStringOrNull(fields)
                "itemType" -> fields.itemType = reader.nextNullableString(fields)
                "tmdbId" -> fields.tmdbId = reader.nextNullableString(fields)
                "tvdbId" -> fields.tvdbId = reader.nextNullableString(fields)
                "imdbId" -> fields.imdbId = reader.nextNullableString(fields)
                "kitsuId" -> fields.kitsuId = reader.nextNullableString(fields)
                "provider" -> fields.provider = reader.nextStringOrNull(fields)
                "source" -> fields.source = reader.nextStringOrNull(fields)
                "scopeKind" -> fields.scopeKind = reader.nextStringOrNull(fields)
                "season" -> fields.season = reader.nextIntOrNull()
                "episode" -> fields.episode = reader.nextIntOrNull()
                "clipType" -> fields.clipType = reader.nextStringOrNull(fields)
                "title" -> fields.title = reader.nextNullableString(fields)
                "language" -> fields.language = reader.nextNullableString(fields)
                "site" -> fields.site = reader.nextStringOrNull(fields)
                "externalVideoId" -> fields.externalVideoId = reader.nextNullableString(fields)
                "playbackKind" -> fields.playbackKind = reader.nextNullableString(fields)
                "youtubeId" -> fields.youtubeId = reader.nextNullableString(fields)
                "providerUrlHash" -> fields.providerUrlHash = reader.nextNullableString(fields)
                "redactedUrl" -> fields.redactedUrl = reader.nextNullableString(fields)
                "confidence" -> fields.confidence = reader.nextStringOrNull(fields)
                "fetchedAtMs" -> fields.fetchedAtMs = reader.nextLongOrNull()
                "expiresAtMs" -> fields.expiresAtMs = reader.nextLongOrNull()
                "staleUntilMs" -> fields.staleUntilMs = reader.nextLongOrNull()
                "sourceTrace" -> fields.sourceTrace = reader.nextStringListOrNull(fields)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return fields
    }

    private fun readLegacyRecord(rawJson: String, expectedKey: String): StoredMediaClipRecord? {
        return try {
            StringReader(rawJson).use { sr ->
                JsonReader(sr).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        null
                    } else {
                        readRecordValue(reader, expectedKey)
                    }
                }
            }
        } catch (_: JsonParseException) {
            null
        } catch (_: JsonIOException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun writeAndSwapLocked(records: Map<String, StoredMediaClipRecord>): Boolean {
        return if (writeLocked(records)) {
            state.records.clear()
            state.records.putAll(records)
            state.loaded = true
            true
        } else {
            false
        }
    }

    private fun writeLocked(records: Map<String, StoredMediaClipRecord>): Boolean {
        var temp: File? = null
        return try {
            file.parentFile?.mkdirs()
            temp = tempFile()
            FileOutputStream(temp).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(STORE_SCHEMA_VERSION)
                        writer.name("records")
                        writer.beginObject()
                        for ((key, record) in records) {
                            writer.name(key)
                            gson.toJson(record, StoredMediaClipRecord::class.java, writer)
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
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextLongOrNull(): Long? {
        return when (peek()) {
            JsonToken.NUMBER,
            JsonToken.STRING -> nextString().toLongOrNull()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextStringOrNull(fields: RecordFields): String? {
        return when (peek()) {
            JsonToken.STRING -> nextString()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                fields.invalid = true
                skipValue()
                null
            }
        }
    }

    private fun JsonReader.nextNullableString(fields: RecordFields): String? =
        nextStringOrNull(fields)

    private fun JsonReader.nextStringListOrNull(fields: RecordFields): List<String>? {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return null
        }
        if (peek() != JsonToken.BEGIN_ARRAY) {
            fields.invalid = true
            skipValue()
            return null
        }
        val values = mutableListOf<String>()
        beginArray()
        while (hasNext()) {
            val value = nextStringOrNull(fields)
            if (value == null) {
                fields.invalid = true
            } else {
                values += value
            }
        }
        endArray()
        return values
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun StoredMediaClipRecord.isValidFor(expectedKey: String): Boolean {
        return try {
            val cleanExpectedKey = cleanKey(expectedKey) ?: return false
            if (key == null || key.trim() != cleanExpectedKey) return false
            if (!cleanExpectedKey.startsWith(KEY_PREFIX)) return false
            if (!hasText(clipId)) return false
            if (!hasText(contentId)) return false
            if (!hasText(provider)) return false
            if (!hasText(source)) return false
            if (!hasText(scopeKind)) return false
            if (!hasText(clipType)) return false
            if (!hasText(site)) return false
            if (!hasText(confidence)) return false
            if (sourceTrace == null) return false
            if (sourceTrace.any { !hasText(it) }) return false
            if (expiresAtMs <= 0L) return false
            if (staleUntilMs < expiresAtMs) return false
            if (playbackKind == PLAYBACK_YOUTUBE && !hasText(youtubeId) && !hasText(externalVideoId)) return false
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun hasText(value: String?): Boolean =
        value?.trim()?.isNotEmpty() == true

    companion object {
        private const val STORE_SCHEMA_VERSION = 2
        private const val KEY_PREFIX = "media-clip:"
        private const val PLAYBACK_YOUTUBE = "youtube"

        private val states = ConcurrentHashMap<String, SharedState>()

        internal fun resetSharedStateForTest(file: File) {
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
}
