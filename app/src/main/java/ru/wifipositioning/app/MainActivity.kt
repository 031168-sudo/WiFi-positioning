package ru.wifipositioning.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WiFiPositioningApp() }
    }
}

@Composable
fun WiFiPositioningApp() {
    MaterialTheme {
        Surface {
            Text("WiFi Positioning — карта OSM")
        }
    }
}
