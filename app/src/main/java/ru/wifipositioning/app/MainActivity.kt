package ru.wifipositioning.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
import org.osmdroid.views.overlay.Polygon

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var wifi: WifiManager
    private lateinit var r1Rssi: TextView
    private lateinit var r2Rssi: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val plot = arrayOf(
        GeoPoint(55.513380,37.594413), GeoPoint(55.513459,37.594642),
        GeoPoint(55.513159,37.594978), GeoPoint(55.513077,37.594723)
    )
    private val r1=GeoPoint(55.513411,37.594664)
    private val r2=GeoPoint(55.513220,37.594773)
    private val center=GeoPoint(55.51326875,37.594689)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor=Color.rgb(13,16,24); window.navigationBarColor=Color.rgb(10,13,20)
        Configuration.getInstance().userAgentValue=packageName
        wifi=applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val root=FrameLayout(this)
        map=MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setCenter(center); controller.setZoom(18.3) }
        root.addView(map,FrameLayout.LayoutParams(-1,-1))
        map.overlays.add(Polygon(map).apply { points=plot.toList(); fillColor=Color.argb(25,255,55,75); strokeColor=Color.rgb(240,55,75); strokeWidth=3f })
        addRouter(r1,"R1","Роутер 1"); addRouter(r2,"R2","Роутер 2")
        addHeader(root); addControls(root); addPanel(root); addBottom(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){_,i-> val b=i.getInsets(WindowInsetsCompat.Type.systemBars()); root.setPadding(0,b.top,0,b.bottom); i }
        setContentView(root); requestWifiPermission(); startWifiUpdates()
    }

    private fun addHeader(root:FrameLayout){
        val h=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(10),0);setBackgroundColor(Color.argb(242,13,16,24))}
        h.addView(TextView(this).apply{text="◉  WiFi-ПОЗИЦИОНЕР";textSize=18f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-1,1f))
        h.addView(TextView(this).apply{text="● Онлайн";textSize=13f;setTextColor(Color.rgb(75,220,130));gravity=Gravity.CENTER},LinearLayout.LayoutParams(-2,-1))
        val satellite=TextView(this).apply{text="🛰";textSize=20f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=rounded(Color.rgb(40,47,62),12);setOnClickListener{Toast.makeText(this@MainActivity,"Спутниковая подложка будет подключена следующим шагом",Toast.LENGTH_SHORT).show()}}
        h.addView(satellite,LinearLayout.LayoutParams(dp(48),dp(40)).apply{marginStart=dp(8)})
        root.addView(h,FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP))
    }

    private fun addControls(root:FrameLayout){
        val compass=TextView(this).apply{text="N\n↑";textSize=15f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=rounded(Color.argb(210,20,24,34),100)}
        root.addView(compass,FrameLayout.LayoutParams(dp(54),dp(54),Gravity.TOP or Gravity.END).apply{topMargin=dp(68);marginEnd=dp(12)})
        val z=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;background=rounded(Color.argb(225,13,16,24),18)}
        fun b(t:String,a:()->Unit)=TextView(this).apply{text=t;textSize=24f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{a()}}
        z.addView(b("−"){map.controller.zoomOut()},LinearLayout.LayoutParams(dp(56),dp(52)));z.addView(b("⌾"){map.controller.animateTo(center);map.controller.setZoom(18.3)},LinearLayout.LayoutParams(dp(56),dp(52)));z.addView(b("+"){map.controller.zoomIn()},LinearLayout.LayoutParams(dp(56),dp(52)))
        root.addView(z,FrameLayout.LayoutParams(-2,dp(52),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(318)})
    }

    private fun addPanel(root:FrameLayout){
        val p=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8));background=rounded(Color.argb(242,13,16,24),22)}
        p.addView(TextView(this).apply{text="━━━━━━";gravity=Gravity.CENTER;setTextColor(Color.rgb(100,110,125));textSize=9f},LinearLayout.LayoutParams(-1,dp(14)))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val pos=card("ТЕКУЩЕЕ ПОЛОЖЕНИЕ");pos.addView(smallText("X: — м     Y: — м\nТочность: —"))
        val sig=card("WI-FI СИГНАЛЫ");r1Rssi=smallText("R1  Роутер 1     — dBm");r2Rssi=smallText("R2  Роутер 2     — dBm");sig.addView(r1Rssi);sig.addView(r2Rssi)
        row.addView(pos,LinearLayout.LayoutParams(0,dp(84),1f));row.addView(sig,LinearLayout.LayoutParams(0,dp(84),1f));p.addView(row)
        val mode=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(10),0);background=rounded(Color.rgb(25,30,41),14)}
        mode.addView(TextView(this).apply{text="◉  ОТСЛЕЖИВАНИЕ\n    Позиция обновляется в реальном времени";setTextColor(Color.WHITE);textSize=11f},LinearLayout.LayoutParams(0,dp(56),1f))
        mode.addView(TextView(this).apply{text="ПАУЗА";gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE);background=rounded(Color.rgb(55,105,235),12);setOnClickListener{ text=if(text=="ПАУЗА")"ПРОДОЛЖИТЬ" else "ПАУЗА"}},LinearLayout.LayoutParams(dp(118),dp(44)))
        p.addView(mode,LinearLayout.LayoutParams(-1,dp(62)).apply{topMargin=dp(7)})
        root.addView(p,FrameLayout.LayoutParams(-1,dp(245),Gravity.BOTTOM).apply{bottomMargin=dp(64)})
    }
    private fun addBottom(root:FrameLayout){
        val n=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setBackgroundColor(Color.rgb(10,13,20))}
        arrayOf("⌖\nКарта","⠿\nКалибровка","⊙\nТочки","⌁\nИстория","☰\nЕщё").forEachIndexed{i,s->n.addView(TextView(this).apply{text=s;textSize=10f;gravity=Gravity.CENTER;setTextColor(if(i==0)Color.rgb(75,145,255) else Color.rgb(155,162,175))},LinearLayout.LayoutParams(0,dp(64),1f))}
        root.addView(n,FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM))
    }
    private fun addRouter(p:GeoPoint,id:String,name:String){map.overlays.add(Marker(map).apply{position=p;title="$id  $name";snippet="Точка Wi-Fi";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER)})}
    private fun card(t:String)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(7),dp(10),dp(4));background=rounded(Color.rgb(25,30,41),14);addView(TextView(this@MainActivity).apply{text=t;textSize=10f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(145,155,175))})}
    private fun smallText(t:String)=TextView(this).apply{text=t;textSize=12f;setTextColor(Color.WHITE);setPadding(0,dp(5),0,0)}
    private fun rounded(c:Int,r:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun requestWifiPermission(){val p=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION);if(android.os.Build.VERSION.SDK_INT>=33)p.add(Manifest.permission.NEARBY_WIFI_DEVICES);if(p.any{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED})ActivityCompat.requestPermissions(this,p.toTypedArray(),42)}
    private fun startWifiUpdates(){handler.post(object:Runnable{override fun run(){updateWifi();handler.postDelayed(this,3000)}})}
    private fun updateWifi(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;try{wifi.startScan();val l=wifi.scanResults.sortedByDescending{it.level};r1Rssi.text="R1  Роутер 1     "+(l.getOrNull(0)?.level?.toString()?.plus(" dBm")?:"—");r2Rssi.text="R2  Роутер 2     "+(l.getOrNull(1)?.level?.toString()?.plus(" dBm")?:"—")}catch(_:SecurityException){}}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
