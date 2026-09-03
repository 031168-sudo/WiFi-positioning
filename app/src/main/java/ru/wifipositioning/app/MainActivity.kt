package ru.wifipositioning.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class MainActivity : Activity() {
    private lateinit var map: MapView

    private val satelliteSource = XYTileSource(
        "Esri World Imagery", 1, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        val root = FrameLayout(this)
        map = MapView(this)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(55.51326875, 37.594689))
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

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

        val satelliteButton = Button(this).apply {
            text = "Спутник"
            textSize = 13f
            setOnClickListener {
                val satellite = map.tileProvider.tileSource.name() == satelliteSource.name()
                map.setTileSource(if (satellite) TileSourceFactory.MAPNIK else satelliteSource)
                text = if (satellite) "Спутник" else "Карта"
                map.invalidate()
            }
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            marginEnd = dp(12)
        }
        root.addView(satelliteButton, params)
        setContentView(root)
    }

    private fun addMarker(map: MapView, lat: Double, lon: Double, title: String) {
        val marker = Marker(map)
        marker.position = GeoPoint(lat, lon)
        marker.title = title
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(marker)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
