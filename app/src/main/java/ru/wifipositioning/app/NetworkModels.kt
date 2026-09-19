package ru.wifipositioning.app

import java.io.Serializable

/** One access point seen in a Wi-Fi scan result. */
data class ScannedNetwork(
    val ssid: String,
    val bssid: String,
    val capabilities: String,
    val frequency: Int,
    val level: Int
) : Serializable {
    val isHidden: Boolean get() = ssid.isBlank()
    val isOpen: Boolean get() = !Regex("WEP|PSK|EAP|SAE").containsMatchIn(capabilities)
    val isWpa3: Boolean get() = capabilities.contains("SAE")
    val band: String get() = if (frequency >= 5000) "5 ГГц" else "2.4 ГГц"
}

/** A network the user picked to monitor, with credentials needed to reconnect to it for speed tests. */
data class MonitorTarget(
    val ssid: String,
    val bssid: String,
    val isOpen: Boolean,
    val isWpa3: Boolean,
    val password: String?
) : Serializable
