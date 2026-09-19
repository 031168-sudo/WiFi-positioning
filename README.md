# WiFi Net

Android app that scans nearby Wi-Fi networks, lets you pick which ones matter to you,
and then watches them live: signal strength and real throughput, plotted as they happen.

## How it works
1. **Scan** — the launcher screen lists every Wi-Fi network currently in range (SSID,
   band, security, signal level), refreshed every few seconds.
2. **Select** — tick the networks you care about and tap "МОНИТОРИНГ →". Secured
   networks ask for their password once, up front.
3. **Monitor** — a dedicated screen streams two live charts per selected network:
   - **Signal strength (RSSI)** — sampled continuously from regular Wi-Fi scans, no
     reconnect needed.
   - **Throughput (Mbit/s)** — since Android only reports real speed for a network it
     is actively connected to, the app cycles its own connection through each selected
     network in turn (Android 10+, via `WifiNetworkSpecifier`) and runs a short
     download test on it before moving to the next one. Each reconnect triggers the
     system's own Wi-Fi confirmation dialog.

No map, no calibration points, no fingerprinting — just live per-network signal and
speed tracking.
