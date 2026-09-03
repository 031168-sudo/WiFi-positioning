package ru.wifipositioning.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        val map = MapView(this)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(55.51326875, 37.594689))

        addMarker(map, 55.513411, 37.594664, "Wi-Fi роутер 1")
        addMarker(map, 55.513220, 37.594773, "Wi-Fi роутер 2")

        val boundary = Polygon(map)
        boundary.points = listOf(
            GeoPoint(55.513380, 37.594413),
            GeoPoint(55.513459, 37.594642),
            GeoPoint(55.513159, 37.594978),
            GeoPoint(55.513077, 37.594723)
        )
        boundary.fillColor = Color.argb(35, 255, 0, 0)
        boundary.strokeColor = Color.RED
        boundary.strokeWidth = 4f
        map.overlays.add(boundary)

        setContentView(map)
    }

    private fun addMarker(map: MapView, lat: Double, lon: Double, title: String) {
        val marker = Marker(map)
        marker.position = GeoPoint(lat, lon)
        marker.title = title
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(marker)
    }

    override fun onResume() {
        super.onResume()
    }
}
