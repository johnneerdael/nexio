package com.nexio.tv.ui.screens.home.order

enum class RailFamily(val familyRank: Int) {
    TRAKT(0),
    SIMKL(1),
    MDBLIST(2),
    TMDB(3),
    KITSU(4),
    ADDON(5),
}

enum class RailSource {
    PROVIDER_PUBLIC,
    PROVIDER_USER,
    ADDON_CATALOG,
}

enum class RailPublishPolicy {
    PUBLISH_ALWAYS,
    PUBLISH_WHEN_NON_EMPTY,
    PUBLISH_ON_FIRST_PAINT,
}

enum class RailOrderMutationSource {
    ANDROID_ORDER_SCREEN,
    PROVIDER_SETTINGS_SCREEN,
    ACCOUNT_SYNC,
    DEFAULT_BOOTSTRAP,
    MIGRATION,
    MIGRATION_SYNTHETIC_FALLBACK,
    DEBUG_RESET,
}
