package com.example.mapboxrerouting

import android.annotation.SuppressLint
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.mapboxrerouting.databinding.NavigationViewBinding
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.dsl.cameraOptions
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.ui.maps.route.line.model.NavigationRouteLine
import com.mapbox.navigation.ui.maps.route.line.model.RouteLine
import com.mapbox.navigation.ui.maps.route.line.model.toNavigationRouteLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.abs

private val TAG = MainActivity::class.simpleName

class MainActivity : AppCompatActivity() {

    private lateinit var binding: NavigationViewBinding
    private lateinit var mapView: MapView
    private lateinit var mapboxMap: MapboxMap
    private lateinit var mapboxNavigation: MapboxNavigation
    private lateinit var routeLineApi: MapboxRouteLineApi
    private lateinit var routeLineView: MapboxRouteLineView
    private val navigationLocationProvider = NavigationLocationProvider()
    private val annotationApi by lazy { mapView.annotations }
    private val pointAnnotationManager by lazy { annotationApi?.createPointAnnotationManager(mapView) }
    private lateinit var lastLocation: Location
    private var isRecalculating: Boolean = false
    private var lastRecalculation: Date = Date()
    private var remainingWaypoints: List<Point> = emptyList()
    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) { /* not handled */
            lastLocation = rawLocation
        }

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            navigationLocationProvider.changePosition(
                location = enhancedLocation,
                keyPoints = locationMatcherResult.keyPoints,
            )
        }
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            val routeLines = routeUpdateResult.routes.map { RouteLine(it, null) }

            routeLineApi.setNavigationRouteLines(
                routeLines.toNavigationRouteLines()
            ) { value ->
                mapboxMap.getStyle()?.apply {
                    routeLineView.renderRouteDrawData(this, value)
                }
            }
        } else {
            val style = mapboxMap.getStyle()
            if (style != null) {
                routeLineApi.clearRouteLine { value ->
                    routeLineView.renderClearRouteLineValue(
                        style,
                        value
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = NavigationViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mapView = binding.mapView
        mapboxMap = binding.mapView.getMapboxMap()
        binding.mapView.location.apply {
            this.locationPuck = LocationPuck2D(
                bearingImage = ContextCompat.getDrawable(
                    this@MainActivity,
                    com.mapbox.navigation.R.drawable.mapbox_navigation_puck_icon
                )
            )
            setLocationProvider(navigationLocationProvider)
            enabled = true
        }
        initMapboxNavigation()
        binding.mapView.getMapboxMap().apply {
            setCamera(
                cameraForGeometry(
                    geometry = LineString.fromLngLats(Waypoints),
                    padding = EdgeInsets(0.0, 0.0, 0.0, 0.0),
                    bearing = 0.0,
                    pitch = 0.0,
                )
            )
            setCamera(cameraOptions { zoom(16.0) })
        }
        val mapboxRouteLineOptions = MapboxRouteLineOptions.Builder(this)
            .withRouteLineBelowLayerId("road-label")
            .build()
        routeLineApi = MapboxRouteLineApi(mapboxRouteLineOptions)
        routeLineView = MapboxRouteLineView(mapboxRouteLineOptions)

        mapboxMap.loadStyleUri(Style.MAPBOX_STREETS)

        GlobalScope.launch {
            val matching = buildMapboxMatching(this@MainActivity, Waypoints)
            val route = requestRoutes(matching)
            remainingWaypoints = Waypoints
            withContext(Dispatchers.Main) {
                mapboxNavigation.setNavigationRoutes(listOf(route))
            }
        }
    }

    private fun initMapboxNavigation() {
        mapboxNavigation = if (MapboxNavigationProvider.isCreated()) {
            MapboxNavigationProvider.retrieve()
        } else {
            MapboxNavigationProvider.create(
                NavigationOptions.Builder(this.applicationContext)
                    .accessToken(getString(R.string.mapbox_access_token))
                    .isDebugLoggingEnabled(true)
                    .build()
            )
        }
        mapboxNavigation.registerLocationObserver(locationObserver)
        mapboxNavigation.registerRoutesObserver(routesObserver)
        mapboxNavigation.setRerouteController(null)

        mapboxNavigation.startTripSession()

        mapboxNavigation.registerRouteProgressObserver { progress ->
            pointAnnotationManager?.deleteAll()
            progress.upcomingStepPoints?.forEachIndexed { index, point ->
                mapboxMap.getStyle {
                    replaceAnnotationToMap(point, index)
                }
            }

            Log.e(TAG, "Distance remaining: ${progress.distanceRemaining}")
            Log.e(TAG, "Duration remaining: ${progress.durationRemaining}")
            Log.e(TAG, "currentState: ${progress.currentState}")
            Log.e(TAG, "fraction legtraveled: ${progress.currentLegProgress?.fractionTraveled}")
            Log.e(TAG, "Fraction traveled: ${progress.fractionTraveled}")
            Log.e(TAG, "upcomingStepPoints: ${progress.upcomingStepPoints}")

            if (progress.currentState == RouteProgressState.OFF_ROUTE)
                backRoute(fromLocation = lastLocation)
        }

    }

    private fun backRoute(fromLocation: Location) {
        if (isRecalculating) {
            Log.e(TAG, "backrouting is already running")
            return
        }

        if (abs(lastRecalculation.time - Date().time) > 5_000) {
            Log.e(TAG, "last recalculation just happened")
        }

        Log.e(TAG, "backRoute from location: $fromLocation")

        isRecalculating = true
        lastRecalculation = Date()

        val routeWaypoints = remainingWaypoints

        Log.e(TAG, "original routeWaypoints: $routeWaypoints")

        if (routeWaypoints.isEmpty()) {
            Log.e(TAG, "No waypoints to route to")
            return
        }
        // find closest point to route to last location
        val first = remainingWaypoints.minBy {
            Location("").apply {
                longitude = it.longitude()
                latitude = it.latitude()
            }.distanceTo(fromLocation)
        }
        // Remove all points in the array that are earlier than the nearest point
        val index = routeWaypoints.indexOf(first)
        val newRoute = listOf(
            // Prepend current location
            Point.fromLngLat(
                fromLocation.longitude,
                fromLocation.latitude
            )
        ) + routeWaypoints.takeLast(routeWaypoints.size - index)

        Log.e(TAG, "New waypoints: $newRoute")
        if (newRoute.size < 2) {
            isRecalculating = false
            return
        }
        GlobalScope.launch {
            val matching = buildMapboxMatching(this@MainActivity, newRoute)
            val route = requestRoutes(matching)
            withContext(Dispatchers.Main) {
                mapboxNavigation.setRoutes(listOf())
                mapboxNavigation.setNavigationRoutes(listOf(route))
            }
            remainingWaypoints = newRoute
            isRecalculating = false
        }
    }

    private fun replaceAnnotationToMap(point: Point, number: Int) {

        // Set options for the resulting symbol layer.
        val pointAnnotationOptions: PointAnnotationOptions = PointAnnotationOptions()
            // Define a geographic coordinate.
            .withPoint(point)
            .withTextField("$number")

        // Add the resulting pointAnnotation to the map.
        pointAnnotationManager?.create(pointAnnotationOptions)
    }

}