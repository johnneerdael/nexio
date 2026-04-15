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
    fun routeFor(profileId: Int): ProfileModeRoute {
        return when (profileId) {
            1 -> ProfileModeRoute.DefaultLegacyRoute
            in 2..4 -> ProfileModeRoute.SecondaryProfileRoute(profileId)
            else -> ProfileModeRoute.InvalidProfileRoute(profileId)
        }
    }
}
