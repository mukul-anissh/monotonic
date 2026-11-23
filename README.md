# Monotonic — Sub-10ms Synced Ensemble Metronome

Monotonic lets multiple musicians join a virtual rehearsal room on Android and hear the exact same beat simultaneously over wired earphones (3.5mm or USB-C), targeting **under 10ms synchronization accuracy** across devices.

Instead of streaming audio or sending per-beat tick packets, Monotonic synchronizes each phone's monotonic clock (`System.nanoTime()` / `CLOCK_MONOTONIC`) against an authoritative Python server clock (`time.monotonic_ns()`), broadcasts only the future transport schedule, and synthesizes percussive clicks sample-accurately inside a lock-free C++ Google Oboe audio thread with hardware output latency compensation.

---

## Repository Structure

```text
monotonic/
├── backend/                     # Python FastAPI + WebSocket authoritative sync server
│   ├── app/
│   │   ├── clock.py             # Monotonic clock source (time.monotonic_ns)
│   │   ├── main.py              # FastAPI app + /ws endpoint + /health check
│   │   ├── models.py            # RoomState & schedule constants
│   │   └── rooms.py             # Room lifecycle, bar-boundary transitions, broadcasts
│   ├── tests/
│   │   └── test_server.py       # Pytest suite (rooms, SNTP timestamps, bar-snap math)
│   ├── pyproject.toml
│   └── requirements.txt
├── android/                     # Native Android app (Kotlin + Compose + C++ Oboe)
│   └── app/src/
│       ├── main/cpp/            # CMake + Prefab Oboe real-time audio engine & JNI
│       ├── main/java/com/monotonic/metronome/
│       │   ├── audio/           # NativeMetronomeEngine, AudioRouteMonitor, AudioFocusController
│       │   ├── data/            # DataStore preferences (server URL, manual offset)
│       │   ├── model/           # Protocol JSON models & UI state
│       │   ├── network/         # OkHttp WebSocket client (t4 captured on receipt)
│       │   ├── sync/            # ClockSyncMath & ClockSyncManager (SNTP + EMA drift)
│       │   └── ui/              # Jetpack Compose Material 3 dark UI & ViewModel
│       └── test/                # Kotlin unit tests for SNTP math & drift smoothing
└── README.md
```

---

## 1. Running the Backend Locally on Wi-Fi (2–5ms Accuracy)

Running the server on a laptop on the same local Wi-Fi (or mobile hotspot) keeps round-trip times around **2–8ms**, eliminating internet jitter and delivering tight ensemble lock.

### Setup & Start

Using `uv`:
```bash
cd backend
uv venv
uv pip install -e ".[dev]"
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Or using standard `pip`:
```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

### Find Your Laptop's Local IP
* **Linux:** `hostname -I | awk '{print $1}'` (or `ip addr show`)
* **macOS:** `ipconfig getifaddr en0`
* **Windows:** `ipconfig` (look for IPv4 Address under Wireless LAN)

If your firewall is active, allow TCP port `8000` (e.g., `sudo ufw allow 8000/tcp`).

On each Android phone, enter `ws://<YOUR_LAPTOP_IP>:8000/ws` (for example `ws://192.168.1.42:8000/ws`) on the Lobby screen.

---

## 2. Deploying the Backend to a Cloud Host (Remote Sessions)

For musicians rehearsing from different locations, deploy `backend/` to any platform supporting long-lived WebSockets (Fly.io, Railway, Render, DigitalOcean App Platform, or a VPS with Caddy/Nginx TLS):

1. Start command:
   ```bash
   uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1
   ```
   > **Important:** Keep `--workers 1` because room state is stored in memory per process.

2. Ensure TLS (`wss://`) termination forwards WebSocket `Upgrade` / `Connection` headers to `/ws`.
3. Health check endpoint: `GET /health` returns `{"status": "ok", "rooms": <count>}`.
4. Point the Android app's Server URL to `wss://your-domain.example.com/ws`.

---

## 3. Running Tests

### Backend (`pytest`)
```bash
cd backend
uv run pytest -v
```

### Android Unit Tests (Kotlin)
```bash
cd android
./gradlew testDebugUnitTest
```

---

## 4. Building & Installing the Android APK

Prerequisites:
* JDK 17
* Android SDK with Platform 35/36, Build Tools `36.0.0`, NDK `28.2.13676358`, and CMake `3.22.1` (configured automatically via `android/app/build.gradle.kts`).

Build the debug APK:
```bash
cd android
./gradlew assembleDebug
```

The APK will be output to:
`android/app/build/outputs/apk/debug/app-debug.apk`

Install onto two connected test phones via `adb`:
```bash
adb devices
adb -s <DEVICE_1_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <DEVICE_2_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Two-Phone Calibration & Wired Earphone Workflow

1. **Plug in wired earphones** (3.5mm jack or USB-C DAC/headset) on both Android devices. Verify the top route banner displays **Wired Audio Active** (not Bluetooth or Phone Speaker).
2. **Connect to the sync server** on Phone A and Phone B. Tap **Create Room** on Phone A, then enter the 6-character code on Phone B and tap **Join Room**.
3. Wait ~1.5 seconds for the initial 20-ping SNTP burst to finish (the Play button switches from *Syncing clock...* to **PLAY**).
4. Expand **Sync Diagnostics** to confirm low `Min RTT` and `Jitter` (<1–2ms on Wi-Fi) and verify Oboe's reported **Hardware Latency** and **Sharing Mode** (`Exclusive` vs `Shared`).
5. Tap **PLAY** on either phone. Both devices count down the 1.5-second lead-in and lock onto Beat 1 simultaneously.
6. **Hardware Calibration (Optional):** Put Phone A's left earbud in your left ear and Phone B's right earbud in your right ear (or place both wired earbuds against one microphone). If one phone's OEM audio driver reports an inaccurate `getTimestamp()` offset, nudge its **Manual Device Calibration** slider (`-50ms` to `+50ms` in 1ms steps) until the two clicks merge into a single transient. The calibration value is persisted locally per device.
