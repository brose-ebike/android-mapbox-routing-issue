package com.example.mapboxrerouting

import com.mapbox.api.matching.v5.MapboxMapMatching
import com.mapbox.api.matching.v5.models.MapMatchingResponse
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.route.toNavigationRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun requestRoutes(
    mapboxMapMatching: MapboxMapMatching,
): NavigationRoute = withContext(Dispatchers.IO) {
    return@withContext mapboxMapMatching.executeCall().body().toNavigationRoute()!!
}

private fun MapMatchingResponse?.toNavigationRoute(): NavigationRoute? {
    return this?.matchings()?.let { matchingList ->
        matchingList.firstOrNull()?.toDirectionRoute()?.toNavigationRoute(RouterOrigin.Custom())
    }
}
