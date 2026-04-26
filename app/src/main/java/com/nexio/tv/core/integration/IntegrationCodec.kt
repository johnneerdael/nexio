package com.nexio.tv.core.integration

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

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
            gson.fromJson(
                bytes.toString(Charsets.UTF_8),
                object : TypeToken<T>() {}.type
            )
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
