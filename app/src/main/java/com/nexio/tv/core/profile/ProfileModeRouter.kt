package com.nexio.tv.core.profile

import javax.inject.Inject
import javax.inject.Singleton

sealed interface ProfileModeRoute {
    data object DefaultLegacyRoute : ProfileModeRoute
    data class SecondaryProfileRoute(val profileId: Int) : ProfileModeRoute
    data class InvalidProfileRoute(val profileId: Int) : ProfileModeRoute
}

@Singleton
class ProfileModeRouter @Inject constructor() {
    companion object {
        const val DEFAULT_LEGACY_PROFILE_ID = 1
    }

    fun routeFor(profileId: Int): ProfileModeRoute {
        return when (profileId) {
            DEFAULT_LEGACY_PROFILE_ID -> ProfileModeRoute.DefaultLegacyRoute
            in 2..4 -> ProfileModeRoute.SecondaryProfileRoute(profileId)
            else -> ProfileModeRoute.InvalidProfileRoute(profileId)
        }
    }

    fun defaultLegacyProfileId(): Int = DEFAULT_LEGACY_PROFILE_ID

    fun isDefaultLegacy(profileId: Int): Boolean {
        return routeFor(profileId) is ProfileModeRoute.DefaultLegacyRoute
    }
}
