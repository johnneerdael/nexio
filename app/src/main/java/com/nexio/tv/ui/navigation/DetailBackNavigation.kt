package com.nexio.tv.ui.navigation

import androidx.navigation.NavHostController

internal const val DETAIL_BACK_HOME_ROUTE = "home"
internal const val DETAIL_BACK_SEARCH_ROUTE = "search"
internal const val DETAIL_BACK_DETAIL_ROUTE_PREFIX = "detail/"
internal const val DETAIL_BACK_STREAM_ROUTE_PREFIX = "stream/"
internal const val DETAIL_BACK_PLAYER_ROUTE_PREFIX = "player/"

internal data class DetailBackNavigation(
    val popToExistingRoute: String? = null,
    val popUpToRoute: String? = null
)

internal fun popDetailBackToExistingHome(): DetailBackNavigation {
    return DetailBackNavigation(popToExistingRoute = DETAIL_BACK_HOME_ROUTE)
}

internal fun popDetailBackToExistingSearch(): DetailBackNavigation {
    return DetailBackNavigation(popToExistingRoute = DETAIL_BACK_SEARCH_ROUTE)
}

internal fun replaceDetailBackStackWithHome(popUpToRoute: String): DetailBackNavigation {
    return DetailBackNavigation(
        popUpToRoute = popUpToRoute
    )
}

internal fun resolveDetailBackNavigation(
    backStackRoutes: List<String>,
    detailSource: String? = null
): DetailBackNavigation {
    if (detailSource.equals(DETAIL_BACK_SEARCH_ROUTE, ignoreCase = true) &&
        backStackRoutes.any { it == DETAIL_BACK_SEARCH_ROUTE }
    ) {
        return popDetailBackToExistingSearch()
    }

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

internal fun NavHostController.navigateDetailBack(detailSource: String? = null) {
    val action = resolveDetailBackNavigation(
        backStackRoutes = currentBackStack.value.mapNotNull { it.destination.route },
        detailSource = detailSource
    )
    val popToExistingRoute = action.popToExistingRoute
    if (popToExistingRoute != null) {
        popBackStack(popToExistingRoute, inclusive = false)
        return
    }

    val popUpToRoute = requireNotNull(action.popUpToRoute)
    navigate(Screen.Home.route) {
        popUpTo(popUpToRoute) { inclusive = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun NavHostController.navigateDetailBackToHome() {
    navigateDetailBack()
}
