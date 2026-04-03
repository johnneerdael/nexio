package com.nexio.tv.ui.navigation

import androidx.navigation.NavHostController

internal const val DETAIL_BACK_HOME_ROUTE = "home"
internal const val DETAIL_BACK_DETAIL_ROUTE_PREFIX = "detail/"
internal const val DETAIL_BACK_STREAM_ROUTE_PREFIX = "stream/"
internal const val DETAIL_BACK_PLAYER_ROUTE_PREFIX = "player/"

internal data class DetailBackNavigation(
    val popToExistingHome: Boolean,
    val popUpToRoute: String? = null
)

internal fun popDetailBackToExistingHome(): DetailBackNavigation {
    return DetailBackNavigation(popToExistingHome = true)
}

internal fun replaceDetailBackStackWithHome(popUpToRoute: String): DetailBackNavigation {
    return DetailBackNavigation(
        popToExistingHome = false,
        popUpToRoute = popUpToRoute
    )
}

internal fun resolveDetailBackNavigation(backStackRoutes: List<String>): DetailBackNavigation {
    if (backStackRoutes.any { it == DETAIL_BACK_HOME_ROUTE }) {
        return popDetailBackToExistingHome()
    }

    val popUpToRoute = backStackRoutes.firstOrNull(::isDetailBackTransientRoute) ?: DETAIL_BACK_DETAIL_ROUTE_PREFIX
    return replaceDetailBackStackWithHome(popUpToRoute = popUpToRoute)
}

private fun isDetailBackTransientRoute(route: String): Boolean {
    return route.startsWith(DETAIL_BACK_STREAM_ROUTE_PREFIX) ||
        route.startsWith(DETAIL_BACK_PLAYER_ROUTE_PREFIX) ||
        route.startsWith(DETAIL_BACK_DETAIL_ROUTE_PREFIX)
}

internal fun NavHostController.navigateDetailBackToHome() {
    val action = resolveDetailBackNavigation(
        currentBackStack.value.mapNotNull { it.destination.route }
    )
    if (action.popToExistingHome) {
        popBackStack(Screen.Home.route, inclusive = false)
        return
    }

    val popUpToRoute = requireNotNull(action.popUpToRoute)
    navigate(Screen.Home.route) {
        popUpTo(popUpToRoute) { inclusive = true }
        launchSingleTop = true
        restoreState = true
    }
}
