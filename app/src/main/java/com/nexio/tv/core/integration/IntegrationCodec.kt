package com.nexio.tv.core.integration

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader

interface IntegrationCodec<T> {
    val mimeType: String
    fun encode(value: T): ByteArray
    fun decode(bytes: ByteArray): T
}

object StringIntegrationCodec : IntegrationCodec<String> {
    override val mimeType: String = "text/plain; charset=utf-8"

    override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
}

class JsonCodec<T>(
    private val encodeFn: (T) -> ByteArray,
    private val decodeFn: (ByteArray) -> T,
    override val mimeType: String = "application/json"
) : IntegrationCodec<T> {
    override fun encode(value: T): ByteArray = encodeFn(value)

    override fun decode(bytes: ByteArray): T = decodeFn(bytes)
}

inline fun <reified T> gsonCodec(gson: Gson = Gson()): IntegrationCodec<T> =
    JsonCodec(
        encodeFn = { value -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        decodeFn = { bytes ->
            // CLAUDE.md rule #3: stream the body via JsonReader instead of
            // gson.fromJson(bytes.toString(Charsets.UTF_8), type). The String
            // overload wraps the body in a StringReader whose .str field pins
            // the entire UTF-16 char[] (~2x body size) for the parse duration —
            // documented in heap dumps as the source of 205 KiB transient char[]
            // orphans on TVDB extended series. Streaming through
            // InputStreamReader + JsonReader keeps the body as the original
            // ByteArray plus a small (~8 KB) CharsetDecoder buffer.
            ByteArrayInputStream(bytes).use { byteStream ->
                InputStreamReader(byteStream, Charsets.UTF_8).use { reader ->
                    JsonReader(reader).use { jsonReader ->
                        gson.fromJson(jsonReader, object : TypeToken<T>() {}.type)
                    }
                }
            }
        }
    )

object FileCodec : IntegrationCodec<File> {
    override val mimeType: String = "application/octet-stream"

    override fun encode(value: File): ByteArray = value.absolutePath.toByteArray(Charsets.UTF_8)

    override fun decode(bytes: ByteArray): File = File(bytes.toString(Charsets.UTF_8))
}

object ByteArrayIntegrationCodec : IntegrationCodec<ByteArray> {
    override val mimeType: String = "application/octet-stream"

    override fun encode(value: ByteArray): ByteArray = value

    override fun decode(bytes: ByteArray): ByteArray = bytes
}
