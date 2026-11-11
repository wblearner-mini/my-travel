package com.mytravel

import android.Manifest
import android.content.Context
import android.hardware.SensorManager
import android.hardware.SensorEventListener
import android.hardware.SensorEvent
import android.hardware.Sensor
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalConfiguration
import com.mytravel.data.Chat
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapUtils
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.mytravel.data.AppDb
import com.mytravel.data.Hotel
import androidx.activity.result.PickVisualMediaRequest
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.fillMaxWidth
import com.mytravel.data.Place
import com.mytravel.data.Trip
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val KEY_CURRENT_TRIP = "current_trip"
private const val KEY_LAST_LAT = "last_lat"
private const val KEY_LAST_LNG = "last_lng"
private const val KEY_NICKNAME = "nickname"

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(context).dao() }
    val weatherRepo = remember { WeatherRepo(AppDb.get(context).weatherDao()) }
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }

    // TextureMapView (not MapView): its pixels live in the view hierarchy, so Haze can blur them.
    val mapView = remember { TextureMapView(context).apply { onCreate(null) } }
    val map = mapView.map
    val hazeState = rememberHazeState()

    val trips by dao.trips().collectAsState(initial = null)
    var currentTripId by remember { mutableStateOf(prefs.getLong(KEY_CURRENT_TRIP, -1L).takeIf { it > 0 }) }
    val trip = trips?.let { list -> list.find { it.id == currentTripId } ?: list.firstOrNull() }
    val places by remember(trip?.id) { trip?.let { dao.places(it.id) } ?: flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    val hotelDao = remember { AppDb.get(context).hotelDao() }
    val hotels by remember(trip?.id) { trip?.let { hotelDao.hotels(it.id) } ?: flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    var selectedHotelId by remember { mutableStateOf<Long?>(null) }
    val selectedHotel = hotels.find { it.id == selectedHotelId }
    var hotelDraft by remember { mutableStateOf<HotelDraft?>(null) }
    var showAddHotel by remember { mutableStateOf(false) }
    var deletingHotel by remember { mutableStateOf<Hotel?>(null) }
    var parseJob by remember { mutableStateOf<Job?>(null) }

    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var selectedPlaceId by remember { mutableStateOf<Long?>(null) }
    val selectedPlace = places.find { it.id == selectedPlaceId }

    var centeredOnMe by remember { mutableStateOf(false) }
    // Saveable, so after Android rebuilds the page (e.g. while in AMap) a route from "我的位置" keeps
    // that start, and its leg numbers, instead of losing it until the next fix.
    var myLatLng by rememberSaveable { mutableStateOf<LatLng?>(null) }
    // Why there is no position yet, for the name-card open at start: permission refused, or no fix in 15 s.
    var locationDenied by remember { mutableStateOf(false) }
    var locationSlow by remember { mutableStateOf(false) }
    // Heights of what covers the map, for framing a route in the visible part.
    var tabBarHeightPx by remember { mutableIntStateOf(0) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    val insetTopPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    // Live position, updated only after a 100 m move so markers aren't redrawn on every fix.
    var mePos by remember { mutableStateOf<LatLng?>(null) }

    // Route mode. Saveable, so it survives a trip to AMap and back even if Android recreates the activity.
    // routeDay: null = no route open; NO_DATE = the unscheduled group; otherwise the ISO date.
    var routeDay by rememberSaveable { mutableStateOf<String?>(null) }
    // Places ("p<id>") and hotels ("h<id>") the user picked for the route, in their order;
    // null = the whole day of routeDay.
    var routeIds by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var pickingRoute by remember { mutableStateOf(false) }
    var routeModeName by rememberSaveable { mutableStateOf(TravelMode.Drive.name) }
    val routeMode = TravelMode.valueOf(routeModeName)
    var routeStartName by rememberSaveable { mutableStateOf(RouteStart.Me.name) }
    var routeEndHotel by rememberSaveable { mutableStateOf(false) }
    var selectedLeg by rememberSaveable { mutableStateOf<Int?>(null) }
    var navLeg by rememberSaveable { mutableStateOf<Int?>(null) }
    var amapMissing by remember { mutableStateOf(false) }

    var hint by remember { mutableStateOf<String?>(null) }
    // The name-card about "me": open at start and on tapping my avatar; a map tap closes it.
    // Saveable, so coming back from AMap to an open route doesn't bring the card back over it.
    var showProfile by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf<MainTab?>(null) }
    // Current-weather summary per place id; absent = not loaded, null = failed.
    val summaries = remember { mutableStateMapOf<Long, NowWeather?>() }
    // The same per hotel id.
    val hotelSummaries = remember { mutableStateMapOf<Long, NowWeather?>() }
    var weatherHotelId by remember { mutableStateOf<Long?>(null) }
    var weatherPlaceId by remember { mutableStateOf<Long?>(null) }
    var zoom by remember { mutableFloatStateOf(map.cameraPosition.zoom) }
    // Map rotation in degrees (0 = north up), followed live for the compass needle.
    var bearing by remember { mutableFloatStateOf(map.cameraPosition.bearing) }
    var showTripPicker by remember { mutableStateOf(false) }
    var editingTrip by remember { mutableStateOf<Trip?>(null) }
    var creatingTrip by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<Candidate?>(null) }
    var editingPlace by remember { mutableStateOf<Place?>(null) }
    var deletingPlace by remember { mutableStateOf<Place?>(null) }

    fun selectTrip(id: Long) {
        currentTripId = id
        prefs.edit { putLong(KEY_CURRENT_TRIP, id) }
        selectedPlaceId = null
    }

    // From my location (AMap's own) to the spot, by car; AMap's route page has "开始导航" and other modes.
    fun navigateTo(name: String, lat: Double, lng: Double) {
        if (!openAmapRoute(context, null, emptyList(), Stop(name.ifBlank { "目的地" }, lat, lng, null), TravelMode.Drive)) amapMissing = true
    }

    fun focusOn(lat: Double, lng: Double) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), maxOf(map.cameraPosition.zoom, 15f)))
    }

    // Leaves route mode: a tap elsewhere on the map ends it, as it closes any other card.
    fun leaveRoute() {
        routeDay = null
        routeIds = null
        selectedLeg = null
        navLeg = null
    }

    fun pick(c: Candidate, needsAddress: Boolean) {
        leaveRoute()
        tab = null
        showProfile = false
        // A POI already saved in this trip opens its own card instead of being offered again.
        c.poiId?.let { id ->
            places.find { it.poiId == id }?.let {
                candidate = null
                selectedHotelId = null
                selectedPlaceId = it.id
                return
            }
            hotels.find { it.poiId == id }?.let {
                candidate = null
                selectedPlaceId = null
                selectedHotelId = it.id
                return
            }
        }
        selectedPlaceId = null
        // The hotel card is checked before the candidate's, so it must go or it would stay on top.
        selectedHotelId = null
        candidate = c.copy(resolving = needsAddress, infoLoading = c.poiId != null)
        // One AMap ID search gives both the business details for the card and the category: lodging
        // (AMap type 10xxxx) is offered as a hotel rather than a trip place.
        c.poiId?.let { id ->
            scope.launch {
                val info = poiInfo(context, id)
                candidate = candidate?.takeIf { it.poiId == id }?.let { cur ->
                    val hotel = info?.typeCode?.startsWith("10") == true
                    // A hotel takes the POI's own address: reverse geocoding may name a neighbouring building.
                    cur.copy(info = info, infoLoading = false, isHotel = hotel || cur.isHotel, address = if (hotel) info?.address ?: cur.address else cur.address)
                }
            }
        }
        if (!needsAddress) return
        scope.launch {
            val address = reverseGeocode(context, c.lat, c.lng)
            // Only fill in if the user is still looking at the same point.
            candidate = candidate?.takeIf { it.lat == c.lat && it.lng == c.lng }?.let {
                it.copy(
                    resolving = false,
                    // A hotel already holds its POI's own address, which is the more precise one.
                    address = if (it.isHotel && it.address != null) it.address else address?.first,
                    // A long-pressed spot is named by its address, unless the user already typed a name.
                    name = if (it.name.isBlank()) address?.second.orEmpty() else it.name,
                )
            }
        }
    }

    fun addPlace(c: Candidate, tripId: Long) = scope.launch {
        val id = dao.insert(
            Place(
                tripId = tripId, name = c.name.trim(), address = c.address, lat = c.lat, lng = c.lng,
                poiId = c.poiId, sortOrder = dao.maxSortOrder(tripId) + 1,
            ),
        )
        candidate = null
        selectedPlaceId = id
    }

    fun enableMyLocation() {
        map.myLocationStyle = MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            .interval(5000)
            // Our own avatar marker shows the position (a marker can be tapped; AMap's dot cannot).
            .showMyLocation(false)
        demoPosition?.let { map.setLocationSource(DemoLocationSource(it)) }
        map.isMyLocationEnabled = true
    }

    fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        locationDenied = result.values.none { it }
        if (!locationDenied) {
            enableMyLocation()
            hint = "正在定位…"
        } else {
            hint = "未授权定位，仍可手动浏览地图"
        }
    }

    // Demo builds only: a sample trip instead of an empty phone (no-op otherwise).
    LaunchedEffect(Unit) { seedDemoTrip(context) }

    // Open at the user's position: last known fix immediately, then the first live fix.
    LaunchedEffect(Unit) {
        // A demo build never opens on the last real position.
        val lat = demoPosition?.latitude?.toFloat() ?: prefs.getFloat(KEY_LAST_LAT, Float.NaN)
        val lng = demoPosition?.longitude?.toFloat() ?: prefs.getFloat(KEY_LAST_LNG, Float.NaN)
        if (!lat.isNaN()) map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat.toDouble(), lng.toDouble()), 14f))
        if (hasLocationPermission()) {
            enableMyLocation()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // "My location" is drawn as the user's avatar; tapping it opens a name-card about "me".
    val avatarMarker = remember { mutableStateOf<Marker?>(null) }
    // A newly picked photo waiting to be framed; the avatar changes only after "使用".
    var cropPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var avatarVersion by remember { mutableIntStateOf(0) }
    var nickname by remember { mutableStateOf(prefs.getString(KEY_NICKNAME, null) ?: "旅行者") }
    var myAddress by remember { mutableStateOf<String?>(null) }
    // Weather at my position, shown in a fixed chip at the top right, under the search bar.
    var meWeather by remember { mutableStateOf<NowWeather?>(null) }
    var meWeatherOpen by remember { mutableStateOf(false) }
    // Weather at the picked spot, for its card (read through the 30-minute cache).
    var candidateWeather by remember { mutableStateOf<NowWeather?>(null) }
    var candidateWeatherOpen by remember { mutableStateOf(false) }
    val placeCount by remember { dao.placeCount() }.collectAsState(initial = 0)
    val visitedCount by remember { dao.visitedCount() }.collectAsState(initial = 0)
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { loadForCrop(context, uri) }
                .onSuccess { cropPhoto = it }
                .onFailure { hint = "头像图片无法读取，请换一张" }
        }
    }

    // Which way the phone faces (degrees from north, clockwise), from the rotation sensor while the
    // app is in front; null until the first reading or on phones without the sensor.
    var heading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(lifecycle) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                // getOrientation is relative to the device's natural (portrait) top; add the screen's rotation.
                val turn = runCatching { context.display?.rotation ?: 0 }.getOrDefault(0) * 90f
                val a = ((Math.toDegrees(ori[0].toDouble()).toFloat()) + turn + 360f) % 360f
                // Light smoothing across the 359° → 0° wrap.
                heading = heading?.let { h -> (h + (((a - h + 540f) % 360f) - 180f) * 0.3f + 360f) % 360f } ?: a
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                Lifecycle.Event.ON_PAUSE -> sm?.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sm?.unregisterListener(listener)
        }
    }
    // Redraw the avatar's direction triangle when its angle on screen moves by 3° or more.
    var drawnAngle by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(heading, bearing, avatarVersion, avatarMarker.value) {
        val m = avatarMarker.value ?: return@LaunchedEffect
        val h = heading ?: return@LaunchedEffect
        val angle = (h - bearing + 360f) % 360f
        val last = drawnAngle
        if (last != null && abs(((angle - last + 540f) % 360f) - 180f) < 3f) return@LaunchedEffect
        drawnAngle = angle
        m.setIcon(BitmapDescriptorFactory.fromBitmap(avatarMarkerBitmap(context, headingDeg = angle)))
    }

    DisposableEffect(lifecycle) {
        map.uiSettings.isZoomControlsEnabled = false
        map.setOnMyLocationChangeListener { loc ->
            if (loc == null || (loc.latitude == 0.0 && loc.longitude == 0.0)) return@setOnMyLocationChangeListener
            prefs.edit { putFloat(KEY_LAST_LAT, loc.latitude.toFloat()).putFloat(KEY_LAST_LNG, loc.longitude.toFloat()) }
            if (myLatLng == null) myLatLng = LatLng(loc.latitude, loc.longitude)
            val here = LatLng(loc.latitude, loc.longitude)
            avatarMarker.value?.position = here
            if (mePos.let { it == null || AMapUtils.calculateLineDistance(it, here) > 100f }) mePos = here
            if (avatarMarker.value == null) {
                avatarMarker.value = map.addMarker(
                    // Below trip pins (1) and their weather pills (2), so a nearby place is never hidden by me.
                    MarkerOptions().position(here).anchor(0.5f, 0.5f).zIndex(0.5f)
                        .icon(BitmapDescriptorFactory.fromBitmap(avatarMarkerBitmap(context))),
                ).apply { `object` = MeTag }
            }
            if (hint == "正在定位…") hint = null
            if (!centeredOnMe) {
                centeredOnMe = true
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15f))
            }
        }
        // Plain taps only select; saving always needs the explicit "添加" button.
        map.setOnMapLongClickListener { p -> pick(Candidate("", null, p.latitude, p.longitude, null, showPin = true), true) }
        map.setOnPOIClickListener { poi ->
            pick(Candidate(poi.name, null, poi.coordinate.latitude, poi.coordinate.longitude, poi.poiId, showPin = false), true)
        }
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(p: CameraPosition) {
                bearing = p.bearing
            }
            override fun onCameraChangeFinish(p: CameraPosition) {
                zoom = p.zoom
            }
        })
        map.setOnMapClickListener {
            leaveRoute()
            showProfile = false
            pickingRoute = false
            candidate = null
            selectedPlaceId = null
            selectedHotelId = null
            tab = null
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(places) {
        places.filter { it.id !in summaries }.forEach { p ->
            launch { summaries[p.id] = weatherRepo.now(p.lat, p.lng).getOrNull()?.value }
        }
    }
    LaunchedEffect(hotels) {
        hotels.filter { it.id !in hotelSummaries }.forEach { h ->
            launch { hotelSummaries[h.id] = weatherRepo.now(h.lat, h.lng).getOrNull()?.value }
        }
    }

    // Saved places of the current trip. A pin's `object` is the place id (Long); its weather
    // pill sits above it as a separate marker whose `object` is WeatherTag, so taps can be told apart.
    val placeMarkers = remember { mutableListOf<Marker>() }
    // Within 1 km of me the pill above my avatar already tells the weather there.
    fun nearMe(lat: Double, lng: Double) = mePos?.let { AMapUtils.calculateLineDistance(it, LatLng(lat, lng)) <= NEAR_ME_M } == true
    // Weather pills only from city zoom upward, so a zoomed-out trip map isn't covered in bubbles.
    val showPills = zoom >= PILL_MIN_ZOOM
    LaunchedEffect(places, hotels, selectedPlaceId, selectedHotelId, summaries.toMap(), hotelSummaries.toMap(), showPills, mePos) {
        placeMarkers.forEach { it.remove() }
        placeMarkers.clear()
        // Our own large pins with the name underneath; the weather pill sits just above the pin's head.
        fun addPin(lat: Double, lng: Double, pin: TripPin, title: String, tag: Any, weather: NowWeather?) {
            if (showPills && weather != null && !nearMe(lat, lng)) {
                placeMarkers += map.addMarker(
                    MarkerOptions().position(LatLng(lat, lng)).anchor(0.5f, 1f).zIndex(2f)
                        .icon(BitmapDescriptorFactory.fromBitmap(weatherPill("${weatherGlyph(weather.code)} ${weather.temp.roundToInt()}° ${weather.text}", pin.pinHeight, context))),
                ).apply { `object` = if (tag is Long) WeatherTag(tag) else tag }
            }
            placeMarkers += map.addMarker(
                MarkerOptions().position(LatLng(lat, lng)).title(title).anchor(0.5f, pin.anchorY).zIndex(1f)
                    .icon(BitmapDescriptorFactory.fromBitmap(pin.bitmap)),
            ).apply { `object` = tag }
        }
        places.forEach { p ->
            val selected = p.id == selectedPlaceId
            val color = when {
                selected -> PinColor.SELECTED
                p.visited -> PinColor.DONE
                else -> PinColor.PLACE
            }
            addPin(p.lat, p.lng, tripPin(context, color, R.drawable.ic_pin_flag, p.name, selected), p.name, p.id, summaries[p.id])
        }
        hotels.forEach { h ->
            val selected = h.id == selectedHotelId
            // Tapping a hotel's pill opens the hotel card, like tapping its pin.
            val color = when {
                selected -> PinColor.SELECTED
                h.checkedOut -> PinColor.DONE
                else -> PinColor.HOTEL
            }
            addPin(h.lat, h.lng, tripPin(context, color, R.drawable.ic_pin_hotel, h.name, selected), h.name, HotelTag(h.id), hotelSummaries[h.id])
        }
        map.setOnMarkerClickListener { m ->
            // Any other marker replaces the name-card (and the route) with its own card.
            if (m.`object` != MeTag) showProfile = false
            leaveRoute()
            when (val tag = m.`object`) {
                is HotelTag -> {
                    candidate = null
                    selectedPlaceId = null
                    selectedHotelId = tag.hotelId
                }
                is Long -> {
                    candidate = null
                    selectedHotelId = null
                    selectedPlaceId = tag
                }
                // The pill overlaps its pin, so it opens the same info card; weather is one tap further.
                is WeatherTag -> {
                    candidate = null
                    selectedHotelId = null
                    selectedPlaceId = tag.placeId
                }
                MeTag -> {
                    candidate = null
                    selectedPlaceId = null
                    selectedHotelId = null
                    tab = null
                    showProfile = true
                    myAddress = null
                    m.position?.let { p -> scope.launch { myAddress = reverseGeocode(context, p.latitude, p.longitude)?.second ?: "无法获取地址" } }
                }
            }
            true
        }
    }
    // Read through the 30-minute cache, so this costs at most one QWeather call per half hour.
    LaunchedEffect(myLatLng != null) {
        if (myLatLng == null) return@LaunchedEffect
        while (true) {
            val here = map.myLocation?.takeIf { it.latitude != 0.0 }?.let { LatLng(it.latitude, it.longitude) } ?: myLatLng!!
            weatherRepo.now(here.latitude, here.longitude).getOrNull()?.value?.let { meWeather = it }
            delay(30 * 60_000L)
        }
    }
    // The card is open at start before the first fix; fill in its address once that fix comes.
    LaunchedEffect(myLatLng != null) {
        val here = myLatLng
        if (here == null) {
            delay(15_000)
            locationSlow = true
            return@LaunchedEffect
        }
        if (showProfile && myAddress == null) myAddress = reverseGeocode(context, here.latitude, here.longitude)?.second ?: "无法获取地址"
    }
    LaunchedEffect(candidate?.lat, candidate?.lng) {
        candidateWeather = null
        candidateWeatherOpen = false
        val c = candidate ?: return@LaunchedEffect
        candidateWeather = weatherRepo.now(c.lat, c.lng).getOrNull()?.value
    }
    // A tapped spot, place or hotel moves to the middle of the map left visible above its card. Runs
    // again when the card's height settles (it grows when AMap's details arrive). Route mode frames its own view.
    val focusTarget = candidate?.let { LatLng(it.lat, it.lng) } ?: selectedPlace?.let { LatLng(it.lat, it.lng) }
        ?: selectedHotel?.let { LatLng(it.lat, it.lng) }
    LaunchedEffect(focusTarget, panelHeightPx) {
        val target = focusTarget ?: return@LaunchedEffect
        if (routeDay != null) return@LaunchedEffect
        delay(400)
        val d = context.resources.displayMetrics.density
        val top = insetTopPx + 76 * d
        val bottom = mapView.height - panelHeightPx - tabBarHeightPx
        if (bottom - top < 80 * d) return@LaunchedEffect
        val at = map.projection.toScreenLocation(target)
        val dx = at.x - mapView.width / 2f
        val dy = at.y - (top + bottom) / 2f
        if (abs(dx) > 4 * d || abs(dy) > 4 * d) map.animateCamera(CameraUpdateFactory.scrollBy(dx, dy))
    }
    val candidateMarker = remember { mutableStateOf<Marker?>(null) }
    LaunchedEffect(candidate?.lat, candidate?.lng) {
        candidateMarker.value?.remove()
        candidateMarker.value = candidate?.takeIf { it.showPin }?.let {
            map.addMarker(
                MarkerOptions().position(LatLng(it.lat, it.lng))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
            )
        }
    }

    // ---- Route mode: stops are the day's places in the user's order, optionally after "my position".
    val chosen = routeIds
    val dayPlaces = if (chosen != null) chosen.mapNotNull { k -> places.find { "p${it.id}" == k } }
    else routeDay?.let { day -> places.filter { it.date == day.takeIf { d -> d != NO_DATE } } }.orEmpty()
    val me = myLatLng
    // A picked route has its hotels as stops already, so it offers no hotel start or end.
    val day = if (chosen != null) null else routeDay?.takeIf { it != NO_DATE }
    // The hotel slept in the night before (morning start) and the one for tonight (evening end).
    val morningHotel = day?.let { d -> hotels.find { it.checkInDate < d && d <= it.checkOutDate } }
    val eveningHotel = day?.let { d -> hotels.find { it.checkInDate <= d && d < it.checkOutDate } }
    val availableStarts = listOfNotNull(
        RouteStart.Me.takeIf { me != null }, RouteStart.Hotel.takeIf { morningHotel != null }, RouteStart.First,
    )
    val routeStart = RouteStart.valueOf(routeStartName).takeIf { it in availableStarts } ?: availableStarts.first()
    val stops = buildList {
        if (routeDay == null) return@buildList
        when (routeStart) {
            RouteStart.Me -> me?.let { add(Stop("我的位置", it.latitude, it.longitude, null, isMe = true)) }
            RouteStart.Hotel -> morningHotel?.let { add(Stop(it.name, it.lat, it.lng, null)) }
            RouteStart.First -> Unit
        }
        if (chosen != null) chosen.forEach { k ->
            places.find { "p${it.id}" == k }?.let { add(Stop(it.name, it.lat, it.lng, it.id)) }
            hotels.find { "h${it.id}" == k }?.let { add(Stop(it.name, it.lat, it.lng, null)) }
        } else dayPlaces.forEach { add(Stop(it.name, it.lat, it.lng, it.id)) }
        if (routeEndHotel) eveningHotel?.let { add(Stop(it.name, it.lat, it.lng, null)) }
    }
    // Key on coordinates and mode only, so editing a note doesn't re-plan; a new order or mode does.
    val routeKey = stops.map { it.lat to it.lng } to routeMode
    // Legs are kept with the route they were planned for. Stops change at once (a new start, the hotel
    // end switched off) while new legs arrive later; showing the old, longer list against the new
    // stops read past their end and crashed the route panel (2026-11-11). Until they match, every
    // leg shows as still being planned.
    var planned by remember { mutableStateOf<Pair<Any?, List<Result<Leg>?>>>(null to emptyList()) }
    val legs = planned.second.takeIf { planned.first == routeKey } ?: List((stops.size - 1).coerceAtLeast(0)) { null }
    LaunchedEffect(routeKey) {
        val key = routeKey
        if (stops.size < 2) {
            planned = key to emptyList()
            return@LaunchedEffect
        }
        planned = key to List(stops.size - 1) { null }
        for (i in 0 until stops.size - 1) {
            val r = planLeg(context, stops[i], stops[i + 1], routeMode)
            planned = key to planned.second.toMutableList().also { it[i] = r }
        }
    }
    // Frames the stops in the part of the map left visible between the search bar and the panels.
    fun fitVisible(points: List<LatLng>) {
        if (points.isEmpty()) return
        val b = LatLngBounds.builder()
        points.forEach(b::include)
        val d = context.resources.displayMetrics.density
        // Search bar (16 + 52 + 8 dp), a pin's height above its point (~56 dp) and the weather pill
        // on top of it (26 + 2 dp), plus a little air, so the topmost stop shows whole; below, its
        // name label (~24 dp).
        val top = (insetTopPx + 172 * d).toInt()
        val bottom = (panelHeightPx + tabBarHeightPx + 32 * d).toInt()
        // Keep at least a sliver of map, or AMap can't fit anything.
        val room = mapView.height - top - bottom
        map.animateCamera(
            if (room > 120 * d) CameraUpdateFactory.newLatLngBoundsRect(b.build(), (40 * d).toInt(), (40 * d).toInt(), top, bottom)
            else CameraUpdateFactory.newLatLngBounds(b.build(), (64 * d).toInt()),
        )
    }
    // Re-framed when the panel's height settles too (it grows as legs arrive, or after the
    // assistant panel closes).
    LaunchedEffect(routeDay, routeIds, stops.size, panelHeightPx) {
        if (routeDay == null || stops.isEmpty()) return@LaunchedEffect
        delay(300)
        fitVisible(stops.map { LatLng(it.lat, it.lng) })
    }
    val routeLines = remember { mutableListOf<Polyline>() }
    LaunchedEffect(legs, selectedLeg, routeDay) {
        routeLines.forEach { it.remove() }
        routeLines.clear()
        if (routeDay == null) return@LaunchedEffect
        val d = context.resources.displayMetrics.density
        legs.forEachIndexed { i, r ->
            val leg = r?.getOrNull() ?: return@forEachIndexed
            val highlighted = selectedLeg == null || selectedLeg == i
            routeLines += map.addPolyline(
                PolylineOptions().addAll(leg.polyline).width((if (highlighted) 7 else 5) * d)
                    .color(if (highlighted) android.graphics.Color.rgb(30, 91, 205) else android.graphics.Color.argb(150, 30, 91, 205))
                    // Below the base map's labels (text z-index 0, AMap.setMapTextZIndex) and below
                    // every marker, so the route never hides a place's pin or a map label.
                    .zIndex(if (highlighted) -1f else -2f),
            )
        }
    }

    // ---- AI assistant: tools read the latest state through `snap` and change data only via the DAO.
    val snap = rememberUpdatedState(
        AppSnapshot(
            trip, places, hotels, selectedPlace, candidate,
            routeDay?.let { if (routeIds != null) dayPlaces.map { p -> p.date }.distinct().singleOrNull().orEmpty() else if (it == NO_DATE) "" else it },
            map.cameraPosition.target,
            // Live fix for "how far is it from me now"; myLatLng only holds the first fix.
            map.myLocation?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }?.let { LatLng(it.latitude, it.longitude) } ?: myLatLng,
        ),
    )
    val host = remember {
        object : AssistantHost {
            override val context = context
            override val weather = weatherRepo
            override fun snapshot() = snap.value.copy(mapCenter = map.cameraPosition.target)
            override fun showRoute(date: String?, mode: TravelMode, start: RouteStart, endAtHotel: Boolean) {
                // Every card that is drawn before the route panel must go, or the route opens hidden.
                showProfile = false
                candidate = null
                selectedHotelId = null
                pickingRoute = false
                selectedPlaceId = null
                selectedLeg = null
                navLeg = null
                routeModeName = mode.name
                routeStartName = start.name
                routeEndHotel = endAtHotel
                routeIds = null
                routeDay = date ?: NO_DATE
                // The assistant panel would cover the route; the reply stays in the chat.
                tab = null
            }
            override fun focus(lat: Double, lng: Double) = focusOn(lat, lng)
            override suspend fun addPlace(place: Place) = dao.insert(place.copy(sortOrder = dao.maxSortOrder(place.tripId) + 1))
            override suspend fun place(id: Long) = dao.place(id)
            override suspend fun updatePlace(place: Place) = dao.update(place)
            override suspend fun deletePlace(place: Place) = dao.delete(place)
            override suspend fun reorder(ordered: List<Place>) {
                // Reuse the day's existing sort keys in the new order; only the order column is written.
                dao.setOrder(ordered.map { it.sortOrder }.sorted().zip(ordered).map { (k, p) -> p.id to k })
            }
        }
    }
    val chatItems = remember { mutableStateListOf<ChatItem>() }
    var session by remember { mutableStateOf(AssistantSession(host)) }
    // The running AI turn lives here, not in the panel, so closing the panel doesn't cancel it.
    var aiJob by remember { mutableStateOf<Job?>(null) }
    fun stopAi() {
        aiJob?.cancel()
        aiJob = null
    }
    val chatDao = remember { AppDb.get(context).chatDao() }
    val chats by chatDao.chats().collectAsState(initial = emptyList())
    var chatId by remember { mutableStateOf<Long?>(null) }

    fun saveChat() {
        val title = (chatItems.firstOrNull { it is ChatItem.User } as? ChatItem.User)?.text?.take(30) ?: return
        val chat = Chat(
            id = chatId ?: 0, title = title, updatedAtMillis = System.currentTimeMillis(),
            itemsJson = itemsToJson(chatItems), historyJson = session.historyJson(), poisJson = session.poisJson(),
        )
        scope.launch {
            if (chatId == null) chatId = chatDao.insert(chat) else chatDao.update(chat)
        }
    }

    fun navigate(from: Stop, vias: List<Stop>, to: Stop) {
        // A null start lets AMap use its own live position, which is fresher than ours.
        if (!openAmapRoute(context, from.takeIf { !it.isMe }, vias, to, routeMode)) amapMissing = true
    }

    BackHandler(enabled = pickingRoute || showProfile || candidate != null || selectedPlaceId != null || weatherPlaceId != null || routeDay != null || selectedHotelId != null || tab != null) {
        when {
            pickingRoute -> pickingRoute = false
            showProfile -> showProfile = false
            weatherPlaceId != null -> weatherPlaceId = null
            selectedHotelId != null -> selectedHotelId = null
            candidate == null && selectedPlaceId == null && routeDay != null -> leaveRoute()
            candidate == null && selectedPlaceId == null -> tab = null
            else -> {
                candidate = null
                selectedPlaceId = null
            }
        }
    }

    val density = LocalDensity.current
    var searching by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().hazeSource(hazeState))

        // The tab bar is pinned to the bottom edge and the panels sit right above it; neither moves
        // with the keyboard (only the status bar and cutouts are avoided; the bar pads the nav bar).
        Box(Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
          // Weather where I am, pinned under the search bar (its 16 dp margin + ~52 dp height); hidden
          // while searching so it doesn't sit on the results.
          // Demo data must say so on screen.
          if (demoPosition != null && !searching) {
              Text(
                  "演示定位", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A4B00),
                  modifier = Modifier.align(Alignment.TopStart).padding(top = 80.dp, start = 16.dp)
                      .glass(hazeState, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
              )
          }
          meWeather?.takeIf { !searching }?.let { w ->
              Text(
                  buildAnnotatedString {
                      withStyle(SpanStyle(color = Color(0xFF5F6368), fontSize = 12.sp)) { append("当前位置  ") }
                      append("${weatherGlyph(w.code)} ${w.temp.roundToInt()}° ${w.text}")
                  },
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 16.dp)
                      .glass(hazeState, RoundedCornerShape(16.dp)).clickable { meWeatherOpen = true }
                      .padding(horizontal = 12.dp, vertical = 6.dp),
              )
          }
          Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // Full width, so the locate button's End alignment holds whether or not a panel is shown.
            // The tab bar already covers this much of the keyboard's height, so panels that follow
            // the keyboard (imePadding) only add the rest and sit 8 dp above it.
            Column(
                Modifier.fillMaxWidth()
                    .consumeWindowInsets(PaddingValues(bottom = with(density) { tabBarHeightPx.toDp() }))
                    .padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Compass: the needle points to north on screen; a tap turns the map north up, or
                // south up when it already is.
                if (tab != MainTab.Ai) Box(
                    Modifier.align(Alignment.End).size(52.dp).glass(hazeState, CircleShape).clickable {
                        val target = if (abs(bearing) < 1f || abs(bearing - 360f) < 1f) 180f else 0f
                        // Same centre, zoom and tilt; changeBearing alone shifted the view sideways each time.
                        val cp = map.cameraPosition
                        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(cp.target, cp.zoom, cp.tilt, target)))
                    },
                    contentAlignment = Alignment.Center,
                ) { CompassIcon(bearing) }
                if (tab != MainTab.Ai) Box(
                    Modifier.align(Alignment.End).size(52.dp)
                        .glass(hazeState, CircleShape)
                        .clickable {
                            val here = map.myLocation
                            when {
                                !hasLocationPermission() -> permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                                here == null || here.latitude == 0.0 -> {
                                    enableMyLocation()
                                    hint = "还没有定位结果，请稍候再试"
                                }
                                else -> {
                                    focusOn(here.latitude, here.longitude)
                                    hint = null
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { LocateIcon() }

                hint?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A4B00),
                        modifier = Modifier.glass(hazeState, RoundedCornerShape(12.dp)).clickable { hint = null }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                val c = candidate
                // Only the card is measured for framing a route; the compass and locate buttons
                // above it sit at the edge and may overlap the route.
                Box(Modifier.fillMaxWidth().onSizeChanged { panelHeightPx = it.height }) {
                when {
                    weatherPlaceId != null || hotelDraft != null -> Unit // a full sheet covers the map; no card beneath it
                    searching -> Unit // hidden, not closed: the panel comes back when the search is cancelled
                    showProfile -> ProfileCard(
                        haze = hazeState, nickname = nickname, avatarVersion = avatarVersion, address = myAddress,
                        locatingText = when {
                            myLatLng == null && locationDenied -> "未授权定位"
                            myLatLng == null && locationSlow -> "暂时无法定位"
                            else -> "正在获取当前位置…"
                        },
                        stats = ProfileStats(trips?.size ?: 0, placeCount, visitedCount),
                        weather = meWeather?.let { "${weatherGlyph(it.code)} ${it.temp.roundToInt()}° ${it.text}" },
                        // Without a position the sheet has nothing to show, and would pop up later on its own.
                        onWeather = { if ((avatarMarker.value?.position ?: myLatLng) != null) meWeatherOpen = true else hint = "还没有定位结果，暂时无法查看当前位置天气" },
                        onRename = { n -> nickname = n; prefs.edit { putString(KEY_NICKNAME, n) } },
                        onChangeAvatar = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onLogout = { scope.launch { ServerApi.logout() } },
                    )
                    tab == MainTab.Ai -> AssistantScreen(
                        haze = hazeState,
                        modifier = Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.74f).dp),
                        session = session, items = chatItems, chats = chats, currentChatId = chatId,
                        onNewChat = {
                            stopAi()
                            chatItems.clear()
                            chatId = null
                            session = AssistantSession(host)
                        },
                        onOpenChat = { c ->
                            stopAi()
                            val items = itemsFromJson(c.itemsJson)
                            chatItems.clear()
                            chatItems.addAll(items)
                            chatId = c.id
                            session = AssistantSession(host, c.historyJson, c.poisJson, items.filterIsInstance<ChatItem.Pending>().maxOfOrNull { it.id } ?: 0)
                        },
                        onDeleteChat = { c ->
                            scope.launch { chatDao.delete(c) }
                            if (c.id == chatId) {
                                stopAi()
                                chatItems.clear()
                                chatId = null
                                session = AssistantSession(host)
                            }
                        },
                        onChanged = ::saveChat,
                        scope = scope, job = aiJob, onJob = { aiJob = it },
                    )
                    parseJob?.isActive == true -> Column(
                        Modifier.fillMaxWidth().glass(hazeState, RoundedCornerShape(28.dp)).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("正在识别订单截图…", style = MaterialTheme.typography.titleMedium)
                        Text("由阿里云百炼 qwen3.8-flash 识别，通常需要几秒到十几秒。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            parseJob?.cancel()
                            parseJob = null
                        }) { Text("取消识别") }
                    }
                    selectedHotel != null -> HotelCard(
                        haze = hazeState, hotel = selectedHotel,
                        weather = hotelSummaries[selectedHotel.id]?.let { "${weatherGlyph(it.code)} ${it.temp.roundToInt()}° ${it.text}" },
                        onWeather = { weatherHotelId = selectedHotel.id },
                        onNavigate = { navigateTo(selectedHotel.name, selectedHotel.lat, selectedHotel.lng) },
                        onToggleCheckedOut = { scope.launch { hotelDao.update(selectedHotel.copy(checkedOut = !selectedHotel.checkedOut)) } },
                        onEdit = { hotelDraft = HotelDraft(null, null, null, selectedHotel) },
                        onDelete = { deletingHotel = selectedHotel },
                        onClose = { selectedHotelId = null },
                    )
                    c != null -> CandidateCard(
                        haze = hazeState, candidate = c, trips = trips.orEmpty(), currentTrip = trip,
                        onNameChange = { candidate = c.copy(name = it) },
                        onAdd = { target ->
                            if (target != null) {
                                addPlace(c, target.id)
                                // Show the trip the place went into.
                                if (target.id != trip?.id) selectTrip(target.id)
                            } else {
                                pendingAdd = c
                                creatingTrip = true
                            }
                        },
                        onAddHotel = { target ->
                            // The hotel form saves into the shown trip, so show the chosen one first.
                            if (target.id != trip?.id) selectTrip(target.id)
                            hotelDraft = HotelDraft(null, null, null, null, SearchHit(c.name, c.address, null, c.lat, c.lng, c.poiId.orEmpty()))
                            candidate = null
                        },
                        onNavigate = { navigateTo(c.name, c.lat, c.lng) },
                        onClose = { candidate = null },
                        weather = candidateWeather?.let { "${weatherGlyph(it.code)} ${it.temp.roundToInt()}° ${it.text}" },
                        onWeather = { candidateWeatherOpen = true },
                    )
                    selectedPlace != null -> PlaceCard(
                        haze = hazeState, place = selectedPlace,
                        weather = summaries[selectedPlace.id]?.let { "${weatherGlyph(it.code)} ${it.temp.roundToInt()}° ${it.text}" },
                        onWeather = { weatherPlaceId = selectedPlace.id },
                        onToggleVisited = { scope.launch { dao.update(selectedPlace.copy(visited = !selectedPlace.visited)) } },
                        onNavigate = { navigateTo(selectedPlace.name, selectedPlace.lat, selectedPlace.lng) },
                        onEdit = { editingPlace = selectedPlace },
                        onDelete = { deletingPlace = selectedPlace },
                        onClose = { selectedPlaceId = null },
                    )
                    pickingRoute -> RoutePickPanel(
                        haze = hazeState, places = places, hotels = hotels, initial = routeIds.orEmpty(),
                        onCancel = { pickingRoute = false },
                    ) { ids ->
                        pickingRoute = false
                        selectedPlaceId = null
                        selectedLeg = null
                        navLeg = null
                        routeIds = ArrayList(ids)
                        // A route the user picks is a driving one; only the assistant may ask for walking.
                        routeModeName = TravelMode.Drive.name
                        routeDay = ROUTE_PICKED
                    }
                    routeDay != null -> RoutePanel(
                        haze = hazeState,
                        title = if (routeIds != null) "自选路线" else (routeDay?.takeIf { it != NO_DATE }?.let(::formatDate) ?: "未安排日期") + " 路线",
                        stops = stops, legs = legs, mode = routeMode,
                        start = routeStart, availableStarts = availableStarts,
                        endAtHotel = routeEndHotel && eveningHotel != null, canEndAtHotel = eveningHotel != null,
                        // Only places have a stay to set; the start and a hotel at the end count as 0.
                        stayMinutes = stops.map { st -> if (st.placeId == null) 0 else dayPlaces.find { it.id == st.placeId }?.stayMinutes },
                        selectedLeg = selectedLeg, navLeg = navLeg,
                        onStart = {
                            routeStartName = it.name
                            selectedLeg = null
                            navLeg = null
                        },
                        onEndAtHotel = {
                            routeEndHotel = it
                            selectedLeg = null
                            navLeg = null
                        },
                        onSelectLeg = { i ->
                            selectedLeg = if (selectedLeg == i) null else i
                            legs.getOrNull(i)?.getOrNull()?.let { leg -> fitVisible(leg.polyline) }
                        },
                        onNavigateLeg = { i ->
                            navLeg = i
                            selectedLeg = i
                            navigate(stops[i], emptyList(), stops[i + 1])
                        },
                        onNavigateAll = {
                            navLeg = null
                            navigate(stops.first(), stops.subList(1, stops.size - 1), stops.last())
                        },
                        onClose = ::leaveRoute,
                    )
                    tab == MainTab.Hotels -> HotelsPanel(
                        haze = hazeState, trip = trip, hotels = hotels,
                        onAdd = { showAddHotel = true },
                        onPick = { h ->
                            selectedPlaceId = null
                            selectedHotelId = h.id
                            focusOn(h.lat, h.lng)
                        },
                    )
                    tab == MainTab.Trip && trips != null -> TripPanel(
                        haze = hazeState, trip = trip, places = places, hotels = hotels,
                        onOpenTrips = { showTripPicker = true },
                        onCreateTrip = { creatingTrip = true },
                        onPickHotel = { h ->
                            selectedPlaceId = null
                            selectedHotelId = h.id
                            focusOn(h.lat, h.lng)
                        },
                        onPick = { p ->
                            selectedPlaceId = p.id
                            focusOn(p.lat, p.lng)
                        },
                        onMove = { a, b -> scope.launch { dao.swapOrder(a, b) } },
                        onRoute = { pickingRoute = true },
                    )
                }
                }
            }
            Box(Modifier.onSizeChanged { tabBarHeightPx = it.height }) { MainTabBar(hazeState, tab) { t ->
                // Tapping the open tab again closes its panel.
                tab = if (tab == t) null else t
                showProfile = false
                pickingRoute = false
                // Switching tabs leaves any open detail (and weather sheet) so the chosen tab's content shows.
                candidate = null
                selectedPlaceId = null
                selectedHotelId = null
                weatherPlaceId = null
                weatherHotelId = null
                meWeatherOpen = false
            } }
          }

            // Drawn last so its results card overlays the bottom panel instead of pushing it.
            Box(Modifier.fillMaxSize()) {
                SearchBar(
                    haze = hazeState,
                    center = { map.cameraPosition.target },
                    onPick = { hit ->
                        pick(Candidate(hit.title, hit.address, hit.lat, hit.lng, hit.poiId, showPin = false), needsAddress = false)
                        focusOn(hit.lat, hit.lng)
                    },
                    onActiveChange = { searching = it },
                )
            }
        }
    }

    cropPhoto?.let { photo ->
        Box(Modifier.fillMaxSize().blockTouches()) {
            AvatarCropper(photo, onCancel = { cropPhoto = null }) { left, top, side ->
                cropPhoto = null
                scope.launch {
                    runCatching { saveAvatar(context, photo, left, top, side) }
                        .onSuccess {
                            avatarMarker.value?.setIcon(BitmapDescriptorFactory.fromBitmap(avatarMarkerBitmap(context, headingDeg = drawnAngle)))
                            drawnAngle = null
                            avatarVersion++
                        }
                        .onFailure { hint = "头像保存失败，请重试" }
                }
            }
        }
    }

    // Weather sheets end above the tab bar, which stays visible and usable.
    val sheetBottom = with(density) { tabBarHeightPx.toDp() }
    candidate?.takeIf { candidateWeatherOpen }?.let { c ->
        BackHandler { candidateWeatherOpen = false }
        WeatherSheetFrame(sheetBottom) {
            WeatherScreen(
                hazeState, Place(id = -2, tripId = 0, name = c.name.ifBlank { "选中的位置" }, lat = c.lat, lng = c.lng, sortOrder = 0),
                weatherRepo, onClose = { candidateWeatherOpen = false },
            )
        }
    }
    if (meWeatherOpen) {
        val at = avatarMarker.value?.position ?: myLatLng
        if (at != null) {
            BackHandler { meWeatherOpen = false }
            WeatherSheetFrame(sheetBottom) {
                WeatherScreen(
                    hazeState, Place(id = -1, tripId = 0, name = "我的位置", lat = at.latitude, lng = at.longitude, sortOrder = 0),
                    weatherRepo, onClose = { meWeatherOpen = false },
                )
            }
        }
    }

    hotels.find { it.id == weatherHotelId }?.let { h ->
        BackHandler { weatherHotelId = null }
        WeatherSheetFrame(sheetBottom) {
            // Negative ids keep the weather screen's per-place state apart from real places.
            WeatherScreen(
                hazeState, Place(id = -1000 - h.id, tripId = h.tripId, name = h.name, lat = h.lat, lng = h.lng, sortOrder = 0),
                weatherRepo, onClose = { weatherHotelId = null },
            )
        }
    }

    places.find { it.id == weatherPlaceId }?.let { p ->
        WeatherSheetFrame(sheetBottom) {
            WeatherScreen(hazeState, p, weatherRepo, onClose = { weatherPlaceId = null })
        }
    }

    val pickOrder = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectedPlaceId = null
        parseJob = scope.launch {
            val jpeg = prepareImage(context, uri)
            val result = parseHotelOrder(jpeg)
            hotelDraft = HotelDraft(jpeg, result.getOrNull(), result.exceptionOrNull()?.message, null)
            parseJob = null
        }
    }
    hotelDraft?.let { draft ->
        trip?.let { t ->
            // Ends above the tab bar, which stays visible; the tab bar's share of the keyboard
            // height is consumed so the form only rises by the rest when typing.
            val tabBar = with(density) { tabBarHeightPx.toDp() }
            Box(
                Modifier.fillMaxSize().blockTouches().statusBarsPadding().displayCutoutPadding()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = tabBar + 8.dp)
                    .consumeWindowInsets(PaddingValues(bottom = tabBar)),
            ) {
                HotelScreen(
                    haze = hazeState, draft = draft, tripId = t.id, tripStart = t.startDate, tripEnd = t.endDate,
                    mapCenter = { map.cameraPosition.target },
                    onSave = { h ->
                        hotelDraft = null
                        scope.launch {
                            selectedHotelId = if (h.id == 0L) hotelDao.insert(h) else h.id.also { hotelDao.update(h) }
                        }
                        focusOn(h.lat, h.lng)
                    },
                    onClose = { hotelDraft = null },
                )
            }
        }
    }
    if (showAddHotel) {
        AlertDialog(
            onDismissRequest = { showAddHotel = false },
            title = { Text("添加酒店") },
            text = {
                Text(
                    "从订单截图识别：你选的这一张图片会发送给阿里云百炼 qwen3.8-flash 识别，约 0.004 元/次" +
                        "（新用户免费额度内不另收费）。识别结果需要你核对确认后才会保存，图片不会保存在 App 中。\n\n" +
                        "也可以不使用 AI，直接手动填写。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddHotel = false
                    pickOrder.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("选择订单截图") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddHotel = false
                    hotelDraft = HotelDraft(null, null, null, null)
                }) { Text("手动填写") }
            },
        )
    }
    deletingHotel?.let { h ->
        ConfirmDialog("删除「${h.name}」？", "删除后无法恢复。", onConfirm = {
            scope.launch { hotelDao.delete(h) }
            deletingHotel = null
            selectedHotelId = null
        }, onDismiss = { deletingHotel = null })
    }

    if (amapMissing) {
        AlertDialog(
            onDismissRequest = { amapMissing = false },
            title = { Text("未安装高德地图") },
            text = { Text("导航需要高德地图 App。路线仍可在本 App 内查看。") },
            confirmButton = {
                TextButton(onClick = {
                    amapMissing = false
                    openAmapInStore(context)
                }) { Text("去安装") }
            },
            dismissButton = { TextButton(onClick = { amapMissing = false }) { Text("取消") } },
        )
    }

    if (showTripPicker) {
        TripPickerDialog(
            trips = trips.orEmpty(), currentId = trip?.id,
            onSelect = {
                selectTrip(it.id)
                showTripPicker = false
            },
            onCreate = {
                showTripPicker = false
                creatingTrip = true
            },
            onEdit = {
                showTripPicker = false
                editingTrip = trip
            },
            onDelete = {
                showTripPicker = false
                trip?.let { scope.launch { dao.delete(it) } }
                selectedPlaceId = null
            },
            onDismiss = { showTripPicker = false },
        )
    }
    if (creatingTrip || editingTrip != null) {
        TripEditDialog(
            trip = editingTrip,
            onDismiss = {
                creatingTrip = false
                editingTrip = null
                pendingAdd = null
            },
            onSave = { t ->
                val add = pendingAdd
                creatingTrip = false
                editingTrip = null
                pendingAdd = null
                scope.launch {
                    if (t.id == 0L) {
                        val id = dao.insert(t)
                        selectTrip(id)
                        add?.let { addPlace(it, id) }
                    } else {
                        dao.update(t)
                    }
                }
            },
        )
    }
    editingPlace?.let { p ->
        PlaceEditDialog(p, trip, onDismiss = { editingPlace = null }, onSave = {
            scope.launch { dao.update(it) }
            editingPlace = null
        })
    }
    deletingPlace?.let { p ->
        ConfirmDialog("删除「${p.name}」？", "删除后无法恢复。", onConfirm = {
            scope.launch { dao.delete(p) }
            deletingPlace = null
            selectedPlaceId = null
        }, onDismiss = { deletingPlace = null })
    }
}

private const val PILL_MIN_ZOOM = 11f
private const val NEAR_ME_M = 1000f
private const val NO_DATE = "-"
private const val ROUTE_PICKED = "picked"

/** Full-screen sheets sit on top of the map; taps on their empty parts must not reach the map or search bar. */
/** A full-height weather sheet over the map that stops [bottom] above the screen's edge (the tab bar). */
@Composable
private fun WeatherSheetFrame(bottom: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(bottom = bottom).blockTouches().statusBarsPadding().displayCutoutPadding()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
    ) { content() }
}

@Composable
private fun Modifier.blockTouches(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

private class WeatherTag(val placeId: Long)
private object MeTag
private class HotelTag(val hotelId: Long)

/** A rounded label with transparent space below it so it sits just above the place pin. */
private fun weatherPill(text: String, pinHeight: Int, context: Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * d
        color = android.graphics.Color.rgb(32, 33, 36)
    }
    val padX = 10 * d
    val h = 26 * d
    val w = paint.measureText(text) + padX * 2
    val bmp = Bitmap.createBitmap(w.toInt(), (h + pinHeight + 2 * d).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(235, 255, 255, 255) }
    c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, bg)
    val baseline = h / 2 - (paint.descent() + paint.ascent()) / 2
    c.drawText(text, padX, baseline, paint)
    return bmp
}

/**
 * Returns (full formatted address, the same address without the leading province and city, used as
 * a long-press name), or null when the service call fails.
 */
private suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): Pair<String, String>? =
    withContext(Dispatchers.IO) {
        try {
            val r = GeocodeSearch(context).getFromLocation(RegeocodeQuery(LatLonPoint(lat, lng), 100f, GeocodeSearch.AMAP))
            // The SDK may give no address (null) for some spots; treat that as a failed lookup, not a crash.
            val full = r?.formatAddress?.takeIf { it.isNotBlank() } ?: return@withContext null
            val short = full.removePrefix(r.province.orEmpty()).removePrefix(r.city.orEmpty()).ifBlank { full }
            full to short
        } catch (e: AMapException) {
            null
        }
    }

private val blurStyle = HazeBlurStyle {
    blurRadius(24.dp)
    colorEffects(listOf(HazeColorEffect.tint(Color.White.copy(alpha = 0.35f))))
    noiseFactor(0.05f)
}

// Glass = real backdrop blur of the map (Haze default "balanced" quality), chosen 2026-11-06 on device.
// Every glass surface also takes the touches on its empty parts, so a tap or long press on a panel
// never falls through to the map below (long press there would drop a new pin).
fun Modifier.glass(state: HazeState, shape: Shape): Modifier =
    clip(shape)
        .pointerInput(Unit) { awaitEachGesture { do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed }) } }
        .hazeBlur(input = HazeInput.Sources(state), style = blurStyle)
        .border(1.dp, Color.White.copy(alpha = 0.7f), shape)
