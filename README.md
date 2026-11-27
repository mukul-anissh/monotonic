# Monotonic: Sub 10ms Synced Ensemble Metronome

Monotonic lets multiple musicians join a virtual rehearsal room on Android and hear the exact same beat simultaneously over wired earphones (3.5mm or USB-C), targeting **under 10ms synchronization accuracy** across devices.

Instead of streaming audio or sending per-beat tick packets, Monotonic synchronizes each phone's monotonic clock (`System.nanoTime()` / `CLOCK_MONOTONIC`) against an authoritative Python server clock (`time.monotonic_ns()`), broadcasts only the future transport schedule, and synthesizes percussive clicks sample-accurately inside a lock-free C++ Google Oboe audio thread with hardware output latency compensation. On local Wi-Fi, devices discover the sync server automatically via mDNS, share and join rooms via 100% offline QR codes, and stay synchronized in the background or with the screen off via an Android foreground service.

---

## Repository Structure

```text
monotonic/
├── backend/                     # Python FastAPI + WebSocket authoritative sync server
│   ├── app/
│   │   ├── clock.py             # Monotonic clock source (time.monotonic_ns)
│   │   ├── main.py              # FastAPI app, /ws endpoint, /health check, mDNS lifespan
│   │   ├── mdns.py              # AsyncZeroconf _syncmetro._tcp.local. advertisement & LAN helpers
│   │   ├── models.py            # RoomState & schedule constants
│   │   └── rooms.py             # Room lifecycle, bar-boundary transitions, broadcasts
│   ├── tests/
│   │   ├── test_mdns.py         # Pytest suite for mDNS config, port/IP detection & lifespan
│   │   └── test_server.py       # Pytest suite (rooms, SNTP timestamps, bar-snap math)
│   ├── pyproject.toml
│   ├── requirements.txt
│   └── uv.lock
├── android/                     # Native Android app (Kotlin + Compose + C++ Oboe)
│   └── app/src/
│       ├── main/cpp/            # CMake + Prefab Oboe real-time audio engine & JNI
│       ├── main/java/com/monotonic/metronome/
│       │   ├── audio/           # NativeMetronomeEngine, AudioRouteMonitor, AudioFocusController, AccentPattern
│       │   ├── data/            # DataStore preferences (server URL, calibration offset, solo state)
│       │   ├── model/           # Protocol JSON models & UI state
│       │   ├── network/         # OkHttp WebSocket client, mDNS discovery, URL normalizer, QR codec, reconnect policy
│       │   ├── service/         # MetronomeService foreground service, wake/Wi-Fi lock & notification policy
│       │   ├── session/         # MetronomeSessionController (persistent playback, sync & reconnect lifecycle)
│       │   ├── sync/            # ClockSyncMath, ClockSyncManager (SNTP + EMA drift), SoloScheduler
│       │   ├── ui/              # Jetpack Compose Material 3 dark UI (Sync Room, offline QR share/scanner, Settings) & ViewModel
│       │   └── MonotonicApplication.kt
│       └── test/                # Kotlin unit tests (SNTP, solo, accents, URL, QR, mDNS, reconnect, service policy)
└── README.md
```

---

## 1. Running the Backend Locally on Wi-Fi (2–5ms Accuracy)

Running the server on a laptop on the same local Wi-Fi (or mobile hotspot) keeps round-trip times around **2–8ms**, eliminating internet jitter and delivering tight ensemble lock.

### Setup & Start

Using `uv`:
```bash
cd backend
uv sync --extra dev
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

### Automatic LAN Discovery (mDNS)

* On startup, FastAPI registers an mDNS service of type `_syncmetro._tcp.local.` (`TXT`: `path=/ws`, `name=<hostname>`, `version=1`) and logs the advertised service name and LAN WebSocket URL (`ws://<LAN_IP>:<PORT>/ws`).
* **Optional environment variables:**
  * `PORT`: Server port fallback when `--port` / `-p` is not passed on the CLI (default `8000`).
  * `ADVERTISE_HOST`: Override the auto-detected primary LAN IPv4 address.
  * `DISABLE_MDNS=1`: Disable mDNS broadcasting (automatically skipped under `pytest`).
* **Firewall:** Allow incoming TCP on your server port (e.g., `sudo ufw allow 8000/tcp`) and UDP `5353` for mDNS discovery if restricted.
* **Connecting on Android:** Tap the top-left **Solo** pill to open **Sync Room**. If one local server is found via mDNS, it is selected automatically; if multiple are found, pick one from the server chips. You can also tap **Change** to pick a server or type a shorthand address (such as `192.168.1.42`, `192.168.1.42:6969`, or a domain), which is normalized silently (`ws://` / `wss://` and `/ws`).

#### Manual Local IP Lookup (Fallback)
* **Linux:** `hostname -I | awk '{print $1}'` (or `ip addr show`)
* **macOS:** `ipconfig getifaddr en0`
* **Windows:** `ipconfig` (look for IPv4 Address under Wireless LAN)

---

## 2. Deploying the Backend to a Cloud Host (Remote Sessions)

For musicians rehearsing from different locations, deploy `backend/` to any platform supporting long-lived WebSockets (Fly.io, Railway, Render, DigitalOcean App Platform, or a VPS with Caddy/Nginx TLS):

1. Start command:
   ```bash
   DISABLE_MDNS=1 uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1
   ```
   > **Important:** Keep `--workers 1` because room state is stored in memory per process. (`DISABLE_MDNS=1` is recommended in cloud containers; if omitted, mDNS registration errors also log a warning cleanly without crashing startup.)
2. Ensure TLS (`wss://`) termination forwards WebSocket `Upgrade` / `Connection` headers to `/ws`.
3. Health check endpoint: `GET /health` returns `{"status": "ok", "rooms": <count>}`.
4. On Android, open **Sync Room -> Change** (or **Settings -> Room -> Server Address**) and enter `your-domain.example.com` or `wss://your-domain.example.com/ws`. Explicitly saved `wss://` remote servers are never silently overwritten by local mDNS discovery.

---

## 3. Running Tests

### Backend (`pytest`)
Covers room lifecycle, SNTP timestamps, bar-boundary transitions, no-wall-clock verification (`test_server.py`), and mDNS helper/lifespan behavior (`test_mdns.py`; mDNS network broadcast is automatically skipped in pytest and can also be forced off with `DISABLE_MDNS=1`):
```bash
cd backend
uv run pytest -v
```

### Android Unit Tests (Kotlin)
Covers SNTP clock sync math, solo scheduler, accent patterns, smart URL normalization, QR invite encode/decode, mDNS selection/helper logic, WebSocket reconnect backoff, and foreground service policy:
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

## 5. Two-Phone Rehearsal, Background Sync & Calibration Workflow

1. **Plug in wired earphones** (3.5mm jack or USB-C DAC/headset) on both Android devices. When in a room, an audio route banner warns if playback is routed through Bluetooth or the phone speaker.
2. **Create & share a room:** Tap the top-left **Solo** pill to open **Sync Room** (mDNS auto-selects the LAN server on the same Wi-Fi, or tap **Change** to pick/enter a server). Tap **Create Room** on Phone A, then either:
   * Tap the **QR** button next to the room code on Phone A and scan it on Phone B with **Scan QR** (works 100% offline via ZXing + CameraX with an optional flashlight toggle), or
   * Enter the 6-character code manually on Phone B and tap **Join Room**.
3. **Wait ~1.5 seconds** for the initial SNTP sync burst to finish (`SYNCING` transitions to **PLAY**).
4. Open **Settings -> Diagnostics** to verify low `Min / Median RTT` and `Sync Jitter` (<1–2ms on local Wi-Fi), plus Oboe's reported **Oboe Hardware Latency** and **Oboe Sharing Mode** (`Exclusive` vs `Shared`).
5. Tap **PLAY** on either phone. Both devices count down the 1.5-second lead-in and lock onto Beat 1 simultaneously. Tap beat bars to cycle accents (`Normal -> Accent -> Mute`, distinguished by block height when idle) or change local subdivisions (`1x–4x`) independently per device.
6. **Background & screen-off reliability:** `MetronomeService` runs as a media-playback foreground service whenever playback is active or a Sync Room is joined, holding a CPU wake lock (and low-latency Wi-Fi lock while in a room) with notification controls (**Play/Stop**, **Leave Room/Close**). Transient WebSocket drops automatically attempt to rejoin with exponential backoff (up to 5 attempts), and transient audio focus interruptions duck/mute without leaving the room. For uninterrupted screen-off sessions on aggressive OEM power savers, tap **Settings -> Background Reliability** to grant unrestricted battery access.
7. **Hardware Calibration (Optional):** Put Phone A's left earbud in your left ear and Phone B's right earbud in your right ear (or place both wired earbuds against one microphone). If one phone's OEM audio driver reports an inaccurate `getTimestamp()` offset, open **Settings -> Calibration** and adjust **Manual Timing Offset** (`-50` to `+50 ms` in 1ms steps) until the two clicks merge into a single transient. The calibration value is persisted locally per device.
