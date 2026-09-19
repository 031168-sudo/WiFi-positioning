# WiFi Positioning

Android application for local positioning using Wi-Fi RSSI fingerprinting and OpenStreetMap.

## Planned features
- OpenStreetMap base map
- Custom property boundary and site overlay
- Two or more Wi-Fi access points
- RSSI calibration points
- Wi-Fi fingerprinting position estimation
- Live position marker
- Calibration and tracking modes

## Wi-Fi network monitor
The "Wi-Fi" tab scans for nearby networks and lets you pick which ones to watch.
The monitoring screen then plots two live charts per selected network:
- **Signal strength (RSSI)** — sampled continuously from regular Wi-Fi scans, no
  reconnect needed.
- **Throughput (Mbit/s)** — since Android only reports real speed for a network
  it is actively connected to, the app cycles its own connection through each
  selected network in turn (Android 10+, via `WifiNetworkSpecifier`) and runs a
  short download test on it before moving to the next one. Each reconnect
  triggers the system's own Wi-Fi confirmation UI; secured networks need a
  password entered once before monitoring starts.
