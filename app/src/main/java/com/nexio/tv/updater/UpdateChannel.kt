package com.nexio.tv.updater

enum class UpdateChannel(
    val buildConfigValue: String,
    val assetPrefix: String
) {
    Stable("stable", "nexio-release"),
    EarlyAccess("earlyAccess", "nexio-earlyaccess");

    companion object {
        fun fromBuildConfig(raw: String): UpdateChannel =
            entries.firstOrNull { it.buildConfigValue == raw } ?: Stable
    }
}
