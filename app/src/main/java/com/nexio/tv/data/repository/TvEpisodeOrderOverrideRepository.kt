package com.nexio.tv.data.repository

import android.content.Context
import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TvEpisodeOrderOverrideRepository {
    suspend fun getOrder(tmdbTvId: String): TvEpisodeOrderProvider

    suspend fun setOrder(tmdbTvId: String, provider: TvEpisodeOrderProvider)

    suspend fun clearOrder(tmdbTvId: String)

    suspend fun hasOverride(tmdbTvId: String): Boolean
}

@Singleton
class FileTvEpisodeOrderOverrideRepository(
    private val file: File
) : TvEpisodeOrderOverrideRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(defaultOverrideFile(context.filesDir))

    private val mutex = Mutex()
    private var loaded = false
    private val overrides = linkedMapOf<String, TvEpisodeOrderProvider>()

    override suspend fun getOrder(tmdbTvId: String): TvEpisodeOrderProvider = mutex.withLock {
        val key = toTmdbTvOrderKey(tmdbTvId)
        ensureLoadedLocked()
        overrides[key] ?: TvEpisodeOrderProvider.TMDB_DEFAULT
    }

    override suspend fun setOrder(tmdbTvId: String, provider: TvEpisodeOrderProvider) {
        val key = toTmdbTvOrderKey(tmdbTvId)
        mutex.withLock {
            ensureLoadedLocked()
            val candidate = linkedMapOf<String, TvEpisodeOrderProvider>()
            candidate.putAll(overrides)
            if (provider == TvEpisodeOrderProvider.TMDB_DEFAULT) {
                candidate.remove(key)
            } else {
                candidate[key] = provider
            }
            writeAndSwapLocked(candidate)
        }
    }

    override suspend fun clearOrder(tmdbTvId: String) {
        val key = toTmdbTvOrderKey(tmdbTvId)
        mutex.withLock {
            ensureLoadedLocked()
            if (!overrides.containsKey(key)) return@withLock
            val candidate = linkedMapOf<String, TvEpisodeOrderProvider>()
            candidate.putAll(overrides)
            candidate.remove(key)
            writeAndSwapLocked(candidate)
        }
    }

    override suspend fun hasOverride(tmdbTvId: String): Boolean = mutex.withLock {
        val key = toTmdbTvOrderKey(tmdbTvId)
        ensureLoadedLocked()
        overrides.containsKey(key)
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        overrides.clear()
        if (file.exists()) {
            try {
                readFileLocked()
            } catch (_: IOException) {
                overrides.clear()
            } catch (_: JsonIOException) {
                overrides.clear()
            } catch (_: JsonParseException) {
                overrides.clear()
            } catch (_: IllegalStateException) {
                overrides.clear()
            }
        }
        loaded = true
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
                            "overrides" -> readOverridesLocked(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (schemaVersion != STORE_SCHEMA_VERSION) {
                        overrides.clear()
                    }
                }
            }
        }
    }

    private fun readOverridesLocked(reader: JsonReader) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val key = normalizeTmdbTvEpisodeOrderKey(reader.nextName())
            val provider = reader.nextProviderOrNull()
            if (key != null && provider != null && provider != TvEpisodeOrderProvider.TMDB_DEFAULT) {
                overrides[key] = provider
            }
        }
        reader.endObject()
    }

    private fun writeAndSwapLocked(candidate: LinkedHashMap<String, TvEpisodeOrderProvider>) {
        writeLocked(candidate)
        overrides.clear()
        overrides.putAll(candidate)
        loaded = true
    }

    private fun writeLocked(candidate: Map<String, TvEpisodeOrderProvider>) {
        var temp: File? = null
        try {
            file.parentFile?.mkdirs()
            temp = tempFile()
            FileOutputStream(temp).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(STORE_SCHEMA_VERSION)
                        writer.name("overrides")
                        writer.beginObject()
                        for ((key, provider) in candidate) {
                            writer.name(key).value(provider.name)
                        }
                        writer.endObject()
                        writer.endObject()
                    }
                }
            }
            moveReplacing(temp, file)
        } catch (e: IOException) {
            temp?.delete()
            throw e
        } catch (e: SecurityException) {
            temp?.delete()
            throw e
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

    private fun JsonReader.nextProviderOrNull(): TvEpisodeOrderProvider? {
        return when (peek()) {
            JsonToken.STRING -> runCatching {
                TvEpisodeOrderProvider.valueOf(nextString())
            }.getOrNull()
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

    companion object {
        private const val STORE_SCHEMA_VERSION = 1
        private const val STORE_DIR = "tv-episode-order-v1"
        private const val STORE_FILE = "episode-order-overrides-v1.json"

        private fun defaultOverrideFile(filesDir: File): File =
            File(File(filesDir, STORE_DIR), STORE_FILE)
    }
}
