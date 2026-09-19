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
   - **Link speed (Mbit/s)** — the negotiated Wi-Fi rate for whichever monitored
     network the phone is currently connected to. Always available: no reconnect, no
     approval dialog, and no internet needed behind the access point.
   - **Download throughput** — reported on the status line when the connected network
     actually reaches the internet, measured with a short download.

Android only reports real speed for a network the device is actively connected to, so
the app also tries to connect to each selected network in turn (Android 10+, via
`WifiNetworkSpecifier`). Some devices refuse those app-initiated connections outright;
when that happens the app stops retrying and asks you to connect manually, at which
point that network's charts start filling in.

No map, no calibration points, no fingerprinting — just live per-network signal and
speed tracking.
