package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

internal data class HomeRailOrderStateJson(
    val orderedKeys: List<String> = emptyList(),
    val disabledKeys: List<String> = emptyList(),
    val version: Long = 0L,
    val updatedAtMs: Long = 0L,
    val lastMutationSource: String = RailOrderMutationSource.DEFAULT_BOOTSTRAP.name,
) {
    fun toState(): HomeRailOrderState = HomeRailOrderState(
        orderedKeys = orderedKeys.map(::HomeRailKey),
        disabledKeys = disabledKeys.map(::HomeRailKey).toSet(),
        version = version,
        updatedAtMs = updatedAtMs,
        lastMutationSource = runCatching { RailOrderMutationSource.valueOf(lastMutationSource) }
            .getOrDefault(RailOrderMutationSource.DEFAULT_BOOTSTRAP),
    )

    companion object {
        fun fromState(state: HomeRailOrderState) = HomeRailOrderStateJson(
            orderedKeys = state.orderedKeys.map { it.value },
            disabledKeys = state.disabledKeys.map { it.value },
            version = state.version,
            updatedAtMs = state.updatedAtMs,
            lastMutationSource = state.lastMutationSource.name,
        )
    }
}

class HomeRailOrderStateCodec(private val gson: Gson) {
    fun encode(state: HomeRailOrderState): String =
        gson.toJson(HomeRailOrderStateJson.fromState(state))

    fun decode(json: String?): HomeRailOrderState =
        if (json.isNullOrBlank()) HomeRailOrderState.Empty
        else runCatching { gson.fromJson(json, HomeRailOrderStateJson::class.java).toState() }
            .getOrDefault(HomeRailOrderState.Empty)

    /**
     * CLAUDE.md hard rule #3: streaming JsonReader-based decode. Avoids the
     * StringReader-pinning anti-pattern of `gson.fromJson(rawString, type)` —
     * the reader consumes tokens directly from the underlying stream without
     * ever materialising the full JSON as a String. Used by the file-backed
     * persistence path (HomeRailOrderStore).
     */
    fun decodeFromReader(reader: JsonReader): HomeRailOrderState =
        runCatching {
            gson.fromJson<HomeRailOrderStateJson>(
                reader,
                HomeRailOrderStateJson::class.java
            )?.toState()
        }.getOrNull() ?: HomeRailOrderState.Empty

    /**
     * Streaming JsonWriter-based encode. Mirrors [decodeFromReader]: the
     * writer emits tokens directly to the underlying stream so the JSON is
     * never materialised as a String.
     */
    fun encodeToWriter(state: HomeRailOrderState, writer: JsonWriter) {
        gson.toJson(
            HomeRailOrderStateJson.fromState(state),
            HomeRailOrderStateJson::class.java,
            writer
        )
    }
}
