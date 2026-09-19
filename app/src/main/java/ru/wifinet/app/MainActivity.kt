package ru.wifinet.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** WiFi Net: scans for nearby Wi-Fi networks and lets the user pick which ones to monitor live. */
class MainActivity : Activity() {

    private lateinit var wifi: WifiManager
    private lateinit var listContainer: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var startButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val selected = LinkedHashSet<String>() // by bssid
    private var networks: List<ScannedNetwork> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.rgb(10, 13, 20)
        window.navigationBarColor = Color.rgb(10, 13, 20)
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

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
            text = "📶  WI-FI NET"; textSize = 18f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head, LinearLayout.LayoutParams(-1, dp(56)))

        root.addView(TextView(this).apply {
            text = "Выберите сети, которые нужно отслеживать"
            setTextColor(Color.rgb(150, 158, 175)); textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll.addView(listContainer, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.argb(245, 10, 13, 20))
        }
        countLabel = TextView(this).apply {
            text = "Выбрано: 0"; setTextColor(Color.rgb(150, 158, 175)); textSize = 13f
        }
        bottom.addView(countLabel, LinearLayout.LayoutParams(0, -2, 1f))
        startButton = TextView(this).apply {
            text = "МОНИТОРИНГ →"; typeface = Typeface.DEFAULT_BOLD; textSize = 13f
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = bg(Color.rgb(55, 105, 235), 12)
            alpha = 0.4f
            setOnClickListener { onStartMonitoring() }
        }
        bottom.addView(startButton, LinearLayout.LayoutParams(dp(160), dp(46)))
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(66)))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, i ->
            val x = i.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, x.top, 0, x.bottom); i
        }
        setContentView(root)

        requestPermission()
        handler.post(object : Runnable {
            override fun run() {
                refreshScan()
                handler.postDelayed(this, 3000)
            }
        })
    }

    private fun requestPermission() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (p.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, p.toTypedArray(), 42)
        }
    }

    private fun refreshScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            wifi.startScan()
            networks = wifi.scanResults
                .map { ScannedNetwork(it.SSID, it.BSSID, it.capabilities, it.frequency, it.level) }
                .distinctBy { it.bssid }
                .sortedByDescending { it.level }
            selected.retainAll(networks.map { it.bssid }.toSet())
            renderList()
        } catch (_: SecurityException) {
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        if (networks.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Поиск сетей…"; setTextColor(Color.rgb(150, 158, 175)); textSize = 13f
                setPadding(0, dp(20), 0, 0); gravity = Gravity.CENTER
            })
        }
        networks.forEach { n -> listContainer.addView(networkRow(n)) }
        countLabel.text = "Выбрано: ${selected.size}"
        startButton.alpha = if (selected.isEmpty()) 0.4f else 1f
    }

    private fun networkRow(n: ScannedNetwork): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bg(Color.rgb(24, 30, 42), 14)
        }
        val box = CheckBox(this).apply {
            isEnabled = !n.isHidden
            isChecked = selected.contains(n.bssid)
            setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(n.bssid) else selected.remove(n.bssid)
                countLabel.text = "Выбрано: ${selected.size}"
                startButton.alpha = if (selected.isEmpty()) 0.4f else 1f
            }
        }
        row.addView(box, LinearLayout.LayoutParams(dp(36), -2))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = if (n.isHidden) "<скрытая сеть>" else n.ssid
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        val security = if (n.isHidden) "" else if (n.isOpen) "Открытая" else if (n.isWpa3) "WPA3" else "Защищённая"
        info.addView(TextView(this).apply {
            text = "${n.band} · $security · ${n.bssid}"
            setTextColor(Color.rgb(150, 158, 175)); textSize = 11f
        })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        row.addView(TextView(this).apply {
            text = "${n.level} дБм"; setTextColor(rssiColor(n.level)); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun rssiColor(level: Int) = when {
        level >= -60 -> Color.rgb(70, 225, 130)
        level >= -75 -> Color.rgb(250, 190, 60)
        else -> Color.rgb(240, 80, 80)
    }

    /** Collects a Wi-Fi password for each selected secured network, then launches monitoring. */
    private fun onStartMonitoring() {
        if (selected.isEmpty()) return
        val picked = networks.filter { selected.contains(it.bssid) }
        askPasswordsSequentially(picked, 0, mutableListOf())
    }

    private fun askPasswordsSequentially(
        picked: List<ScannedNetwork>,
        index: Int,
        results: MutableList<MonitorTarget>
    ) {
        if (index >= picked.size) {
            val intent = Intent(this, MonitorActivity::class.java)
            intent.putExtra("targets", ArrayList(results))
            startActivity(intent)
            return
        }
        val n = picked[index]
        if (n.isOpen) {
            results.add(MonitorTarget(n.ssid, n.bssid, true, false, null))
            askPasswordsSequentially(picked, index + 1, results)
            return
        }
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Пароль для «${n.ssid}»")
            .setMessage("Нужен для теста скорости этой сети")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Далее") { _, _ ->
                results.add(MonitorTarget(n.ssid, n.bssid, false, n.isWpa3, input.text.toString()))
                askPasswordsSequentially(picked, index + 1, results)
            }
            .setNegativeButton("Пропустить сеть") { _, _ ->
                askPasswordsSequentially(picked, index + 1, results)
            }
            .show()
    }

    private fun bg(c: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
