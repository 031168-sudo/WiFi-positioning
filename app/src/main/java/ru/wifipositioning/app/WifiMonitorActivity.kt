package ru.wifipositioning.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live-monitors the networks the user picked: RSSI is sampled continuously via regular scans
 * (no reconnect needed), while throughput is measured by cycling the app's own network request
 * through each target in turn, since Android only reports real speed for a network it is
 * actively bound to.
 */
class WifiMonitorActivity : Activity() {

    private lateinit var wifi: WifiManager
    private lateinit var cm: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var targets: List<MonitorTarget> = emptyList()

    private val rssiCharts = mutableMapOf<String, LineChartView>()
    private val throughputCharts = mutableMapOf<String, LineChartView>()
    private val statusViews = mutableMapOf<String, TextView>()

    private var cycleIndex = 0
    private var currentCallback: ConnectivityManager.NetworkCallback? = null
    private var running = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.rgb(10, 13, 20)
        window.navigationBarColor = Color.rgb(10, 13, 20)
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        cm = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        targets = (intent.getSerializableExtra("targets") as? ArrayList<MonitorTarget>) ?: emptyList()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 17))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            setBackgroundColor(Color.argb(245, 10, 13, 20))
        }
        head.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(0, 0, dp(14), 0)
            setOnClickListener { finish() }
        })
        head.addView(TextView(this).apply {
            text = "МОНИТОРИНГ WI-FI"; textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head, LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            list.addView(TextView(this).apply {
                text = "Тест скорости конкретной сети требует Android 10 и новее.\n" +
                    "Ниже показывается только сила сигнала (RSSI)."
                setTextColor(Color.rgb(250, 190, 60)); textSize = 12f
                setPadding(0, 0, 0, dp(10))
            })
        }
        targets.forEach { t -> list.addView(targetCard(t)) }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, i ->
            val x = i.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, x.top, 0, x.bottom); i
        }
        setContentView(root)

        startRssiLoop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targets.isNotEmpty()) {
            testNextThroughput()
        }
    }

    private fun targetCard(t: MonitorTarget): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(24, 30, 42)); cornerRadius = dp(14).toFloat()
            }
        }
        card.addView(TextView(this).apply {
            text = t.ssid; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = t.bssid; setTextColor(Color.rgb(150, 158, 175)); textSize = 10f
            setPadding(0, 0, 0, dp(6))
        })

        card.addView(TextView(this).apply {
            text = "СИГНАЛ (dBm)"; setTextColor(Color.rgb(145, 155, 175)); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        })
        val rssiChart = LineChartView(this).apply {
            lineColor = Color.rgb(80, 150, 255); unit = " дБм"; fixedMin = -100f; fixedMax = -30f
        }
        rssiCharts[t.bssid] = rssiChart
        card.addView(rssiChart, LinearLayout.LayoutParams(-1, dp(80)).apply { bottomMargin = dp(10) })

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(TextView(this).apply {
            text = "СКОРОСТЬ (Мбит/с)"; setTextColor(Color.rgb(145, 155, 175)); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val status = TextView(this).apply {
            text = "ожидание"; setTextColor(Color.rgb(150, 158, 175)); textSize = 10f
        }
        statusViews[t.bssid] = status
        statusRow.addView(status)
        card.addView(statusRow)
        val throughputChart = LineChartView(this).apply {
            lineColor = Color.rgb(70, 225, 130); unit = " Мб/с"; fixedMin = 0f
        }
        throughputCharts[t.bssid] = throughputChart
        card.addView(throughputChart, LinearLayout.LayoutParams(-1, dp(80)))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    // --- RSSI: sampled from regular scan results, no reconnect required -------------------

    private fun startRssiLoop() {
        handler.post(object : Runnable {
            override fun run() {
                sampleRssi()
                if (running) handler.postDelayed(this, 3000)
            }
        })
    }

    private fun sampleRssi() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            wifi.startScan()
            val byBssid = wifi.scanResults.associateBy { it.BSSID }
            targets.forEach { t ->
                byBssid[t.bssid]?.let { rssiCharts[t.bssid]?.addValue(it.level.toFloat()) }
            }
        } catch (_: SecurityException) {
        }
    }

    // --- Throughput: cycle a real connection through each target in turn ------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun testNextThroughput() {
        if (!running || targets.isEmpty()) return
        val target = targets[cycleIndex % targets.size]
        cycleIndex++
        setStatus(target.bssid, "подключение…", Color.rgb(250, 190, 60))

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(target.ssid)
            .setBssid(MacAddress.fromString(target.bssid))
        if (!target.isOpen && !target.password.isNullOrEmpty()) {
            if (target.isWpa3) specifierBuilder.setWpa3Passphrase(target.password)
            else specifierBuilder.setWpa2Passphrase(target.password)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val done = AtomicBoolean(false)
        lateinit var callback: ConnectivityManager.NetworkCallback
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!done.compareAndSet(false, true)) return
                setStatus(target.bssid, "тест скорости…", Color.rgb(80, 150, 255))
                io.execute { runSpeedTest(network, target) { finishCycle(callback) } }
            }

            override fun onUnavailable() {
                if (!done.compareAndSet(false, true)) return
                setStatus(target.bssid, "недоступно", Color.rgb(240, 80, 80))
                finishCycle(callback)
            }
        }
        currentCallback = callback
        cm.requestNetwork(request, callback, 15000)
    }

    private fun runSpeedTest(network: Network, target: MonitorTarget, onDone: () -> Unit) {
        var mbps = -1.0
        try {
            val conn = network.openConnection(URL(SPEED_TEST_URL)) as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.connect()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            val start = System.nanoTime()
            val deadline = start + 4_000_000_000L // measure for up to 4s
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (System.nanoTime() > deadline) break
                }
            }
            conn.disconnect()
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            if (seconds > 0) mbps = (total * 8.0 / 1_000_000.0) / seconds
        } catch (_: Exception) {
        }
        handler.post {
            if (mbps >= 0) {
                setStatus(target.bssid, "%.1f Мбит/с".format(mbps), Color.rgb(70, 225, 130))
                throughputCharts[target.bssid]?.addValue(mbps.toFloat())
            } else {
                setStatus(target.bssid, "ошибка теста", Color.rgb(240, 80, 80))
            }
            onDone()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun finishCycle(callback: ConnectivityManager.NetworkCallback) {
        try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) {}
        if (currentCallback === callback) currentCallback = null
        if (running) handler.postDelayed({ testNextThroughput() }, 2000)
    }

    private fun setStatus(bssid: String, text: String, color: Int) {
        statusViews[bssid]?.apply { this.text = text; setTextColor(color) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        currentCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) {} }
        super.onDestroy()
    }

    companion object {
        private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
    }
}
