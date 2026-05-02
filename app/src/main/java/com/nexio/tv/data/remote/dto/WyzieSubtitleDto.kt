package com.nexio.tv.data.remote.dto

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

/**
 * Moshi DTO mirroring https://docs.wyzie.io `/search` response items.
 *
 * `source` is normalized to a single nullable string by [WyzieSourceJsonAdapter] (Wyzie returns
 * either a string or an array). All other optional fields are tolerant of null/missing.
 */
// Reflective Moshi adapter is used; no @JsonClass(generateAdapter = true)
// because this project does not run the moshi-kotlin-codegen kapt processor.
data class WyzieSubtitleDto(
    val id: String,
    val url: String,
    val format: String,
    val encoding: String?,
    val display: String?,
    val language: String,
    val media: String?,
    val isHearingImpaired: Boolean = false,
    val flagUrl: String? = null,
    @WyzieSourceField val source: String? = null,
    val release: String? = null,
    val releases: List<String>? = null,
    val fileName: String? = null,
    val downloadCount: Int? = null,
    val origin: String? = null,
    val matchedRelease: String? = null,
    val matchedFilter: String? = null,
)

/** Marker used to route the field through [WyzieSourceJsonAdapter]. */
@JsonQualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class WyzieSourceField

/**
 * Adapter that accepts `null`, a string, or a JSON array of strings (taking the first entry).
 */
class WyzieSourceJsonAdapter {
    @FromJson
    @WyzieSourceField
    fun fromJson(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> reader.nextNull<String>()
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.BEGIN_ARRAY -> {
                reader.beginArray()
                var first: String? = null
                while (reader.hasNext()) {
                    val v = if (reader.peek() == JsonReader.Token.STRING) reader.nextString() else {
                        reader.skipValue(); null
                    }
                    if (first == null && v != null) first = v
                }
                reader.endArray()
                first
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    @ToJson
    fun toJson(writer: JsonWriter, @WyzieSourceField value: String?) {
        if (value == null) writer.nullValue() else writer.value(value)
    }
}
