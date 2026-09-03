package ru.wifipositioning.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.*
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var wifi: WifiManager
    private lateinit var r1Rssi: TextView
    private lateinit var r2Rssi: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val plot = arrayOf(
        GeoPoint(55.513380, 37.594413),
        GeoPoint(55.513459, 37.594642),
        GeoPoint(55.513159, 37.594978),
        GeoPoint(55.513077, 37.594723)
    )
    private val router1 = GeoPoint(55.513411, 37.594664)
    private val router2 = GeoPoint(55.513220, 37.594773)
    private val center = GeoPoint(55.51326875, 37.594689)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 16, 24)
        window.navigationBarColor = Color.rgb(10, 13, 20)
        Configuration.getInstance().userAgentValue = packageName
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        val root = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(18.3)
            controller.setCenter(center)
            setBackgroundColor(Color.rgb(18, 22, 30))
        }
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        addPlotBoundary()
        addRouter(router1, "R1", "Роутер 1")
        addRouter(router2, "R2", "Роутер 2")
        addCalibrationPoints()
        schemeOverlay = SchemeOverlay(map)
        map.overlays.add(0, schemeOverlay)

        addHeader(root)
        addMapControls(root)
        addInfoPanel(root)
        addBottomNav(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)

        requestWifiPermission()
        startWifiUpdates()
    }

    private fun addHeader(root: FrameLayout) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            setBackgroundColor(Color.argb(238, 13, 16, 24))
        }
        val title = TextView(this).apply {
            text = "◉  WiFi-ПОЗИЦИОНЕР"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        val online = TextView(this).apply {
            text = "● Онлайн"
            textSize = 13f
            setTextColor(Color.rgb(75, 220, 130))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(online, LinearLayout.LayoutParams(-2, -1))
        val scheme = TextView(this).apply {
            text = "СХЕМА"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(40, 47, 62), 12)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                schemeOverlay.visible = !schemeOverlay.visible
                text = if (schemeOverlay.visible) "СХЕМА" else "КАРТА"
                map.invalidate()
            }
        }
        header.addView(scheme, LinearLayout.LayoutParams(-2, dp(40)))
        root.addView(header, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
    }

    private fun addMapControls(root: FrameLayout) {
        val label = TextView(this).apply {
            text = "СЕТКА 5 м"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.argb(205, 20, 24, 34), 12)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val llp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        llp.topMargin = dp(68)
        llp.marginStart = dp(12)
        root.addView(label, llp)

        val compass = TextView(this).apply {
            text = "N\n↑"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(210, 20, 24, 34), 100)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val clp = FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.END)
        clp.topMargin = dp(68)
        clp.marginEnd = dp(12)
        root.addView(compass, clp)

        val zoom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.argb(225, 13, 16, 24), 18)
            setPadding(dp(4), 0, dp(4), 0)
        }
        fun zoomButton(s: String, action: () -> Unit) = TextView(this).apply {
            text = s
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { action() }
        }
        zoom.addView(zoomButton("−") { map.controller.zoomOut() }, LinearLayout.LayoutParams(dp(56), dp(52)))
        zoom.addView(zoomButton("⌾") { map.controller.animateTo(center); map.controller.setZoom(18.3) }, LinearLayout.LayoutParams(dp(56), dp(52)))
        zoom.addView(zoomButton("+") { map.controller.zoomIn() }, LinearLayout.LayoutParams(dp(56), dp(52)))
        val zlp = FrameLayout.LayoutParams(-2, dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        zlp.bottomMargin = dp(318)
        root.addView(zoom, zlp)
    }

    private fun addInfoPanel(root: FrameLayout) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(Color.argb(242, 13, 16, 24), 22)
        }
        val handle = TextView(this).apply {
            text = "━━━━━━"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(100, 110, 125))
            textSize = 9f
        }
        panel.addView(handle, LinearLayout.LayoutParams(-1, dp(14)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pos = card("ТЕКУЩЕЕ ПОЛОЖЕНИЕ")
        val ptext = TextView(this).apply {
            text = "X: 28.6 м     Y: 56.3 м\nТочность: ±2.4 м"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, dp(5), 0, 0)
        }
        pos.addView(ptext)
        row.addView(pos, LinearLayout.LayoutParams(0, dp(84), 1f))

        val sig = card("WI-FI СИГНАЛЫ")
        r1Rssi = smallText("R1  Роутер 1     — dBm")
        r2Rssi = smallText("R2  Роутер 2     — dBm")
        sig.addView(r1Rssi)
        sig.addView(r2Rssi)
        row.addView(sig, LinearLayout.LayoutParams(0, dp(84), 1f))
        panel.addView(row)

        val mode = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            background = rounded(Color.rgb(25, 30, 41), 14)
        }
        val mt = TextView(this).apply {
            text = "◉  ОТСЛЕЖИВАНИЕ\n    Позиция обновляется в реальном времени"
            setTextColor(Color.WHITE)
            textSize = 11f
        }
        mode.addView(mt, LinearLayout.LayoutParams(0, dp(56), 1f))
        val pause = TextView(this).apply {
            text = "ПАУЗА"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(55, 105, 235), 12)
            setOnClickListener { text = if (text == "ПАУЗА") "ПРОДОЛЖИТЬ" else "ПАУЗА" }
        }
        mode.addView(pause, LinearLayout.LayoutParams(dp(118), dp(44)))
        panel.addView(mode, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(7) })

        val lp = FrameLayout.LayoutParams(-1, dp(245), Gravity.BOTTOM)
        lp.bottomMargin = dp(64)
        root.addView(panel, lp)
    }

    private fun addBottomNav(root: FrameLayout) {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(10, 13, 20))
        }
        val items = arrayOf("⌖\nКарта", "⠿\nКалибровка", "⊙\nТочки", "⌁\nИстория", "☰\nЕщё")
        items.forEachIndexed { i, s ->
            val v = TextView(this).apply {
                text = s
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) Color.rgb(75, 145, 255) else Color.rgb(155, 162, 175))
                setOnClickListener {
                    if (i != 0) Toast.makeText(this@MainActivity, s.substringAfter("\n") + " — следующий этап", Toast.LENGTH_SHORT).show()
                }
            }
            nav.addView(v, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        root.addView(nav, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM))
    }

    private fun addPlotBoundary() {
        map.overlays.add(Polygon(map).apply {
            points = plot.toList()
            fillColor = Color.argb(28, 255, 55, 75)
            strokeColor = Color.rgb(240, 55, 75)
            strokeWidth = dp(3).toFloat()
        })
    }

    private fun addRouter(p: GeoPoint, id: String, name: String) {
        map.overlays.add(Marker(map).apply {
            position = p
            title = "$id  $name"
            snippet = "Точка Wi-Fi"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        })
        map.overlays.add(LabelOverlay(p, id, Color.rgb(170, 80, 245)))
    }

    private fun addCalibrationPoints() {
        val letters = "ABCDEFGHIJKLMNOP"
        var n = 0
        for (r in 1..4) for (c in 0..3) {
            val u = (c + 0.5) / 4.0
            val t = (r - 0.5) / 4.0
            val top = GeoPoint(plot[0].latitude * (1-u) + plot[1].latitude*u, plot[0].longitude*(1-u)+plot[1].longitude*u)
            val bottom = GeoPoint(plot[3].latitude * (1-u) + plot[2].latitude*u, plot[3].longitude*(1-u)+plot[2].longitude*u)
            val p = GeoPoint(top.latitude*(1-t)+bottom.latitude*t, top.longitude*(1-t)+bottom.longitude*t)
            map.overlays.add(LabelOverlay(p, letters[n++].toString(), Color.rgb(50, 205, 125), true))
        }
    }

    private class LabelOverlay(
        private val geo: GeoPoint, private val text: String,
        private val color: Int, private val small: Boolean = false
    ) : Overlay() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val p = Point()
            mapView.projection.toPixels(geo, p)
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), if (small) 13f else 22f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = if (small) 12f else 14f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(text, p.x.toFloat(), p.y.toFloat() + paint.textSize/3f, paint)
        }
    }

    private class SchemeOverlay(private val map: MapView) : Overlay() {
        var visible = true
        private val bitmap = BitmapFactory.decodeResource(map.resources, ru.wifipositioning.app.R.drawable.site_scheme)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val geo = arrayOf(
            GeoPoint(55.513380, 37.594413),
            GeoPoint(55.513459, 37.594642),
            GeoPoint(55.513159, 37.594978),
            GeoPoint(55.513077, 37.594723)
        )
        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || !visible || bitmap == null) return
            val dst = FloatArray(8)
            val p = Point()
            for (i in geo.indices) {
                mapView.projection.toPixels(geo[i], p)
                dst[i*2] = p.x.toFloat()
                dst[i*2+1] = p.y.toFloat()
            }
            val src = floatArrayOf(0f,0f, bitmap.width.toFloat(),0f, bitmap.width.toFloat(),bitmap.height.toFloat(), 0f,bitmap.height.toFloat())
            val matrix = Matrix()
            if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) canvas.drawBitmap(bitmap, matrix, paint)
        }
    }

    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(7), dp(10), dp(4))
        background = rounded(Color.rgb(25, 30, 41), 14)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(145, 155, 175))
        })
    }

    private fun smallText(s: String) = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(0, dp(4), 0, 0)
    }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun requestWifiPermission() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED })
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 42)
    }

    private fun startWifiUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                updateWifi()
                handler.postDelayed(this, 3000)
            }
        })
    }

    private fun updateWifi() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            wifi.startScan()
            val list = wifi.scanResults.sortedByDescending { it.level }
            r1Rssi.text = "R1  Роутер 1     " + (list.getOrNull(0)?.level?.toString()?.plus(" dBm") ?: "—")
            r2Rssi.text = "R2  Роутер 2     " + (list.getOrNull(1)?.level?.toString()?.plus(" dBm") ?: "—")
        } catch (_: SecurityException) { }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
