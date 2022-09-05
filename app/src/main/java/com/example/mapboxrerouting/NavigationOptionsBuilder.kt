package com.example.mapboxrerouting

import android.content.Context
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.matching.v5.MapboxMapMatching
import com.mapbox.geojson.Point

/**
 * @see <a href="https://docs.mapbox.com/api/navigation/map-matching/">Doc</a>
 */
internal fun buildMapboxMatching(
    context: Context,
    waypoints: List<Point>,
): MapboxMapMatching {
    return MapboxMapMatching.builder()
        .accessToken(context.getString(R.string.mapbox_access_token))
        .coordinates(waypoints)
        .steps(true)
        .profile(DirectionsCriteria.PROFILE_CYCLING)
        .annotations(DirectionsCriteria.ANNOTATION_DISTANCE)
        .build()
}