package com.mytravel

import android.location.Location
import android.os.Handler
import android.os.Looper
import com.amap.api.maps.LocationSource
import com.amap.api.maps.model.LatLng

/** Screen-recording builds only (BuildConfig.DEMO_LOCATION = "lat,lng"): the position to show instead of the real one. */
val demoPosition: LatLng? = BuildConfig.DEMO_LOCATION.split(",").takeIf { it.size == 2 }
    ?.let { (lat, lng) -> LatLng(lat.trim().toDouble(), lng.trim().toDouble()) }

/** Feeds the map a fixed position every few seconds in place of AMap's own location client. */
class DemoLocationSource(private val at: LatLng) : LocationSource {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationSource.OnLocationChangedListener? = null
    private val tick = object : Runnable {
        override fun run() {
            listener?.onLocationChanged(
                Location("demo").apply {
                    latitude = at.latitude
                    longitude = at.longitude
                    accuracy = 10f
                    time = System.currentTimeMillis()
                },
            )
            handler.postDelayed(this, 3_000)
        }
    }

    override fun activate(l: LocationSource.OnLocationChangedListener) {
        listener = l
        handler.post(tick)
    }

    override fun deactivate() {
        listener = null
        handler.removeCallbacks(tick)
    }
}
