package ru.wifinet.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
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
class MonitorActivity : Activity() {

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
    private var throughputStarted = false

    /**
     * Some devices refuse app-initiated WifiNetworkSpecifier connections outright (onUnavailable
     * within milliseconds, without ever showing the system's approval dialog). Retrying that in a
     * tight loop hammers the Wi-Fi stack for nothing, so give up on it after a few refusals and
     * fall back to measuring whichever network the phone is already connected to.
     */
    private var specifierRefusals = 0
    private var specifierDisabled = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        CrashLog.install(this)
        CrashLog.logEvent(this, "MonitorActivity onCreate targets=${
            (intent.getSerializableExtra("targets") as? ArrayList<*>)?.size ?: -1
        }")
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
            setOnClickListener {
                CrashLog.logEvent(this@MonitorActivity, "back arrow tapped by user")
                finish()
            }
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
    }

    override fun onResume() {
        super.onResume()
        CrashLog.logEvent(this, "MonitorActivity onResume")
        // The throughput cycle deliberately does NOT start from onCreate: the system only accepts
        // a WifiNetworkSpecifier request from an app it already considers foreground, and at
        // onCreate time this activity isn't resumed yet, so the very first request gets refused.
        if (!throughputStarted) {
            throughputStarted = true
            handler.postDelayed({ startThroughputIfPermitted() }, 1500)
        }
    }

    /**
     * Connecting to a specific network via WifiNetworkSpecifier requires the runtime
     * NEARBY_WIFI_DEVICES permission on Android 13+ (ACCESS_FINE_LOCATION alone is not enough
     * there, even though it's all regular Wi-Fi scanning needs) — request it here, right before
     * it's actually needed, instead of failing silently on every network with a SecurityException.
     */
    private fun startThroughputIfPermitted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || targets.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), NEARBY_WIFI_PERMISSION_REQUEST
            )
            return
        }
        testNextThroughput()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NEARBY_WIFI_PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            testNextThroughput()
        } else {
            targets.forEach {
                setStatus(it.bssid, "нет разрешения «Ближайшие устройства»", Color.rgb(240, 80, 80))
            }
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

    /** The Wi-Fi network the phone is already connected to, if any. */
    @Suppress("DEPRECATION")
    private fun currentWifiNetwork(): Network? = try {
        cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun connectedBssid(): String? = try {
        wifi.connectionInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }
    } catch (_: Exception) {
        null
    }

    private fun scheduleNextCycle(delayMs: Long = 5000) {
        if (running) handler.postDelayed({ testNextThroughput() }, delayMs)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun testNextThroughput() {
        if (!running || targets.isEmpty()) return
        val target = targets[cycleIndex % targets.size]
        cycleIndex++
        CrashLog.logEvent(this, "testNextThroughput -> ${target.ssid} (${target.bssid})")

        // Fast path: if the phone is already on this network, measure straight over it. No
        // reconnect, no system approval dialog, no special permission — this always works.
        val wifiNetwork = currentWifiNetwork()
        if (wifiNetwork != null && connectedBssid().equals(target.bssid, ignoreCase = true)) {
            CrashLog.logEvent(this, "using already-connected network for ${target.ssid}")
            setStatus(target.bssid, "тест скорости…", Color.rgb(80, 150, 255))
            try {
                io.execute { runSpeedTest(wifiNetwork, target) { scheduleNextCycle() } }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start speed test for ${target.ssid}", e)
                scheduleNextCycle()
            }
            return
        }

        if (specifierDisabled) {
            setStatus(target.bssid, "подключитесь к сети вручную", Color.rgb(250, 190, 60))
            scheduleNextCycle()
            return
        }

        setStatus(target.bssid, "подключение…", Color.rgb(250, 190, 60))

        // Requires a non-blank passphrase for any secured network. Missing/invalid credentials,
        // an odd BSSID, or an OEM quirk here must never crash the app — skip to the next target.
        val password: String = target.password ?: ""
        if (!target.isOpen && password.isEmpty()) {
            setStatus(target.bssid, "нет пароля", Color.rgb(240, 80, 80))
            scheduleNextCycle()
            return
        }

        val request: NetworkRequest
        try {
            // SSID only, no setBssid(): pinning the exact AP makes the request far more likely
            // to be refused outright, and the SSID is enough to get connected for a speed test.
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(target.ssid)
            if (!target.isOpen) {
                if (target.isWpa3) specifierBuilder.setWpa3Passphrase(password)
                else specifierBuilder.setWpa2Passphrase(password)
            }
            request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build network request for ${target.ssid}", e)
            setStatus(target.bssid, "ошибка: ${e.shortDescription()}", Color.rgb(240, 80, 80))
            scheduleNextCycle()
            return
        }

        val done = AtomicBoolean(false)
        lateinit var callback: ConnectivityManager.NetworkCallback
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                CrashLog.logEvent(this@MonitorActivity, "onAvailable ${target.ssid}")
                if (!done.compareAndSet(false, true)) return
                specifierRefusals = 0
                setStatus(target.bssid, "тест скорости…", Color.rgb(80, 150, 255))
                // onAvailable can still fire from the system after this screen is on its way
                // out and the executor is already shut down — never let that crash the app.
                try {
                    io.execute { runSpeedTest(network, target) { finishCycle(callback) } }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start speed test for ${target.ssid}", e)
                    finishCycle(callback)
                }
            }

            override fun onUnavailable() {
                CrashLog.logEvent(this@MonitorActivity, "onUnavailable ${target.ssid}")
                if (!done.compareAndSet(false, true)) return
                specifierRefusals++
                if (specifierRefusals >= MAX_SPECIFIER_REFUSALS) {
                    specifierDisabled = true
                    CrashLog.logEvent(this@MonitorActivity, "specifier disabled after $specifierRefusals refusals")
                    targets.forEach {
                        setStatus(it.bssid, "подключитесь к сети вручную", Color.rgb(250, 190, 60))
                    }
                } else {
                    setStatus(target.bssid, "недоступно", Color.rgb(240, 80, 80))
                }
                finishCycle(callback)
            }
        }
        try {
            currentCallback = callback
            CrashLog.logEvent(this, "requestNetwork calling for ${target.ssid}")
            // Passing our main-Looper handler makes the callbacks arrive on the main thread
            // instead of ConnectivityThread.
            cm.requestNetwork(request, callback, handler, 15000)
            CrashLog.logEvent(this, "requestNetwork returned for ${target.ssid}")
        } catch (e: Exception) {
            currentCallback = null
            Log.e(TAG, "requestNetwork failed for ${target.ssid}", e)
            setStatus(target.bssid, "ошибка: ${e.shortDescription()}", Color.rgb(240, 80, 80))
            scheduleNextCycle()
        }
    }

    private fun Exception.shortDescription(): String {
        val name = javaClass.simpleName
        val msg = message?.take(300)
        return if (msg.isNullOrBlank()) name else "$name: $msg"
    }

    private fun runSpeedTest(network: Network, target: MonitorTarget, onDone: () -> Unit) {
        CrashLog.logEvent(this, "runSpeedTest start ${target.ssid} (thread=${Thread.currentThread().name})")
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
        } catch (e: Exception) {
            Log.e(TAG, "Speed test failed for ${target.ssid}", e)
            CrashLog.logEvent(this, "runSpeedTest FAILED ${target.ssid}: ${e.shortDescription()}")
        }
        CrashLog.logEvent(this, "runSpeedTest done ${target.ssid} mbps=$mbps")
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
        scheduleNextCycle()
    }

    // NetworkCallback methods are delivered on ConnectivityManager's own ConnectivityThread, not
    // on the main thread, and touching a view from there throws CalledFromWrongThreadException —
    // so always hop to the main thread here rather than relying on every caller to remember.
    private fun setStatus(bssid: String, text: String, color: Int) {
        handler.post { statusViews[bssid]?.apply { this.text = text; setTextColor(color) } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Pure diagnostic breadcrumbs — no exception involved — to tell apart "something in our code
    // called finish()" from "the OS killed the process outright" when the screen closes on its
    // own with no crash dialog and no entry in the exception-based crash log.
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        CrashLog.logEvent(this, "system back key/gesture triggered")
        super.onBackPressed()
    }

    override fun onPause() {
        CrashLog.logEvent(this, "MonitorActivity onPause isFinishing=$isFinishing")
        super.onPause()
    }

    override fun onStop() {
        CrashLog.logEvent(this, "MonitorActivity onStop isFinishing=$isFinishing")
        super.onStop()
    }

    override fun onDestroy() {
        CrashLog.logEvent(
            this,
            "MonitorActivity onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations"
        )
        running = false
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        currentCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: IllegalArgumentException) {} }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WifiNetMonitor"
        private const val NEARBY_WIFI_PERMISSION_REQUEST = 77
        private const val MAX_SPECIFIER_REFUSALS = 3
        private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
    }
}
