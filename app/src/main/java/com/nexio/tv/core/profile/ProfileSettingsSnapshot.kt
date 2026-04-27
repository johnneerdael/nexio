package com.nexio.tv.core.profile

data class ProfileSettingsSnapshot(
    val displayLanguage: String,
    val region: String,
    val autoplay: Boolean
) {
    init {
        require(displayLanguage.isNotBlank()) { "ProfileSettingsSnapshot.displayLanguage must not be blank" }
        require(region.isNotBlank()) { "ProfileSettingsSnapshot.region must not be blank" }
    }
}
