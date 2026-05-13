package com.nexio.tv.core.artwork.fanarttv

sealed interface FanartTvAvailability {
    data class Available(val apiKey: String) : FanartTvAvailability
    data class Disabled(val reason: String) : FanartTvAvailability

    companion object {
        fun from(rawKey: String): FanartTvAvailability =
            if (rawKey.isBlank()) Disabled("no_build_config_key")
            else Available(rawKey.trim())
    }
}
