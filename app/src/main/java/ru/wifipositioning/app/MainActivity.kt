package ru.wifipositioning.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var schemeOverlay: SchemeOverlay

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

        schemeOverlay = SchemeOverlay(map)
        map.overlays.add(0, schemeOverlay)

        val schemeButton = Button(this).apply {
            text = "Схема"
            textSize = 13f
            setOnClickListener {
                schemeOverlay.visible = !schemeOverlay.visible
                text = if (schemeOverlay.visible) "Скрыть схему" else "Схема"
                map.invalidate()
            }
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(8)
            marginEnd = dp(12)
        }
        root.addView(schemeButton, params)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            params.topMargin = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + dp(8)
            schemeButton.layoutParams = params
            insets
        }
        ViewCompat.requestApplyInsets(root)

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

    private class SchemeOverlay(private val map: MapView) : Overlay() {
        var visible = true
        private val bitmap = BitmapFactory.decodeResource(
            map.resources,
            ru.wifipositioning.app.R.drawable.site_scheme
        )
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Pixel coordinates of the four red boundary vertices in the bundled image.
        // They are mapped to the four real GPS coordinates supplied for the plot.
        private val src = floatArrayOf(
            17.3f, 89.6f,       // top-left
            127.0f, 23.2f,      // top-right
            274.4f, 329.1f,     // bottom-right
            170.2f, 269.1f      // bottom-left
        )

        private val geo = arrayOf(
            GeoPoint(55.513380, 37.594413),
            GeoPoint(55.513459, 37.594642),
            GeoPoint(55.513159, 37.594978),
            GeoPoint(55.513077, 37.594723)
        )

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || !visible || bitmap == null) return

            val projection = mapView.projection
            val dst = FloatArray(8)
            val p = Point()
            for (i in geo.indices) {
                projection.toPixels(geo[i], p)
                dst[i * 2] = p.x.toFloat()
                dst[i * 2 + 1] = p.y.toFloat()
            }

            val matrix = Matrix()
            if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
                canvas.drawBitmap(bitmap, matrix, paint)
            }
        }
    }
}
