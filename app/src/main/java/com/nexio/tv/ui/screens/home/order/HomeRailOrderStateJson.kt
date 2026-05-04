package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson

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
}
