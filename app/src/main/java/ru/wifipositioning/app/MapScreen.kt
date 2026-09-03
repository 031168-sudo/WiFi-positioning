package ru.wifipositioning.app

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val map = remember { createMap(context) }

    DisposableEffect(Unit) {
        onDispose { map.onDetach() }
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { it.invalidate() }
    )
}

private fun createMap(context: Context): MapView {
    Configuration.getInstance().userAgentValue = context.packageName
    val map = MapView(context)
    map.setTileSource(TileSourceFactory.MAPNIK)
    map.setMultiTouchControls(true)
    map.controller.setZoom(17.0)
    // Temporary center; exact site coordinates will be inserted after georeferencing.
    map.controller.setCenter(GeoPoint(55.51326875, 37.594689))

    val r1 = Marker(map).apply {
        position = GeoPoint(55.513411, 37.594664)
        title = "Wi-Fi роутер 1"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }
    val r2 = Marker(map).apply {
        position = GeoPoint(55.513220, 37.594773)
        title = "Wi-Fi роутер 2"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }
    map.overlays.add(r1)
    map.overlays.add(r2)

    // Example boundary placeholder. Real coordinates will come from the user's map.
    val boundary = Polygon(map).apply {
        points = listOf(
            GeoPoint(55.513380, 37.594413),
            GeoPoint(55.513459, 37.594642),
            GeoPoint(55.513159, 37.594978),
            GeoPoint(55.513077, 37.594723)
        )
        fillColor = Color.argb(35, 255, 0, 0)
        strokeColor = Color.RED
        strokeWidth = 4f
        title = "Участок"
    }
    map.overlays.add(boundary)
    return map
}
