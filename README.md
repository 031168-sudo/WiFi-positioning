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

## Also included: "Резонанс" voice monitor

A separate screen (tap the 🎙 icon on the map header) that records microphone
audio, transcribes speech (`SpeechRecognizer`, ru-RU), tracks vocal-tension
metrics (pitch deviation from a calibrated baseline, jitter) and flags
possible text contradictions between statements (word/number overlap
heuristics).

**This is explicitly not a lie detector.** Voice stress has no validated
scientific link to deception — it also reflects nervousness, fatigue, or
ordinary emotion. The screen's own "Ограничения" section spells this out and
should be read before relying on any of its output. Only record other
people's voices with their consent.
