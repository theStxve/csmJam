# 𓆩🜲𓆪 CSM Jam 3.0: The Offline Music Sync & Party Hub

<p align="center">
  <a href="https://github.com/theStxve/csmJam/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/theStxve/csmJam?style=for-the-badge&label=release&color=8A2BE2"></a>
  <img alt="Version" src="https://img.shields.io/badge/version-3.0.0-8A2BE2?style=for-the-badge">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-1.8-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white">
</p>
<p align="center">
  <img alt="Media3" src="https://img.shields.io/badge/Media3-ExoPlayer-FF6F00?style=for-the-badge&logo=youtubemusic&logoColor=white">
  <img alt="Browser Join" src="https://img.shields.io/badge/iPhone%20%26%20Browser-join%20without%20app-000000?style=for-the-badge&logo=safari&logoColor=white">
  <img alt="Sync" src="https://img.shields.io/badge/sync-auto--calibrated%20%C2%B11ms-FFD700?style=for-the-badge">
  <img alt="Offline" src="https://img.shields.io/badge/privacy-100%25%20offline-2EA043?style=for-the-badge&logo=wifi&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-personal%20use-lightgrey?style=for-the-badge">
</p>
<p align="center">
  <img alt="Last commit" src="https://img.shields.io/github/last-commit/theStxve/csmJam?style=flat-square">
  <img alt="Code size" src="https://img.shields.io/github/languages/code-size/theStxve/csmJam?style=flat-square">
  <img alt="Stars" src="https://img.shields.io/github/stars/theStxve/csmJam?style=flat-square">
</p>

> **CSM Jam** turns any group of smartphones into a synchronized, distributed surround-sound system and collaborative party console — **100% offline, decentralized, and with millisecond-level synchronization.**
> Version 3.0 adds acoustic **auto-calibration**, an **own party WLAN**, **crossfade**, **iPhone/browser guests** and a complete **redesign**.

---

## ✦ What's New in CSM Jam 3.0?

### ◈ Acoustic Auto-Calibration
- **Chirp Measurement:** Every device plays a short 40 ms sweep (2 → 8 kHz, Hann window) at a planned session time; the host phone records them and measures how late each one arrives.
- **Bluetooth-Aware:** Measured relative to the host's own chirp, so mic latency and clock mapping cancel out – what remains is the real output delay of each device, including Bluetooth speakers. One tap, and every phone is aligned to the host.
- **Manual Fallback:** "Klick-Test" plus the infinite calibration ruler (momentum + haptic ticks) for fine-tuning by ear.

### ◈ Own Party WLAN (Local-Only Hotspot)
- **No Router Needed:** The host can open its own offline network – for places without WLAN or guest networks that isolate devices. SSID & password are shown (and shareable via QR).
- **Wi-Fi Binding:** App traffic is pinned to the WLAN even when Android flags it as "no internet", so discovery and the connection to the host never leak to mobile data.

### ◈ Browser Join – iPhone & No-APK Guests
- **Zero Install:** Guests without the app (iPhone, iPad, laptops) open `http://<host-ip>:8081` or simply scan the host's QR code with the camera and listen in sync right in the browser.
- **Host Approval:** Every browser guest sends a join request; the host sees name & device (e.g. *iPhone · Safari*) and has to accept or decline. Approved browsers reconnect automatically after screen lock, kicked ones have to ask again.
- **Full Web Player:** Same NTP-lite clock sync with playbackRate drift correction, per-device latency calibration, live queue & participant list, lock-screen controls, megaphone announcements – and Co-DJs can upload songs straight from their phone.

### ◈ Seamless Playback & Crossfade
- **Gapless Hand-Over:** The next track is preloaded on every device; the server announces it ahead of time with the exact end timestamp of the current song, so all phones switch at the same moment without a pause.
- **Synchronized Crossfade:** 0 / 3 / 6 / 10 s transitions, set by the host and applied identically on every device (dual-ExoPlayer engine).
- **Smooth Drift Correction:** Proportional playback-rate correction (±4 %, median-filtered) with learned seek compensation and a monotonic session clock that ignores system time jumps.

### ◈ Robust Sessions
- **Songs Live on the Host:** Guests upload their tracks to the host – songs keep playing after a guest leaves.
- **10-Minute Session Resume:** If the host app gets killed, queue, position, approvals and stats can be resumed from the start screen.
- **Optional Approval for App Guests**, remembered autoplay folder, and a session that survives Activity re-creation inside the foreground service.

### ◈ Shareable Session Wrapped
- Render the party recap (duration, tracks, top DJ, top artist) as an **image** and share it, plus the full **played song list** as text.

### ◈ Complete Redesign
- New design system: near-black ground, layered greys, one accent – with **Space Grotesk**, **Inter** and **JetBrains Mono** (all OFL, bundled offline).
- New Full Player, Start & Session screens; the former 3,700-line `MainActivity` is split into focused modules (`PlaybackEngine`, `JamSession`, `SessionScreen`, …).

### ◈ Quality
- JVM unit tests for the session server protocol and the chirp signal.

---

## ✦ Carried Over from 2.0

- **Dynamic Album-Art Gradient Theming** via Palette API across player, dialogs and status bar.
- **Session Wrapped** live analytics & departure summary.
- **Fair Round-Robin Autoplay Pool:** every participant can contribute a local folder; when the queue runs low (≤ 1 track) the server pulls a random track from the next participant.
- **NFC Tap-to-Join, Offline QR & UDP Multicast Discovery.**
- **AMOLED Battery Saver:** true `#000000`, animations off, audio & megaphone keep running.
- **Live Megafon (Push-to-Talk):** 16 kHz PCM voice over WebSockets with auto-ducking to 15 %; host grants Megaphone / Co-DJ rights per guest.
- **Party-Light Mode:** beat-synced flashlight strobe (Beat-Drop, Fast, Chill) behind an Easter Egg.
- **Swipeable Tabs:** Warteschlange (Queue & PTT) and Session (WLAN, signal, autoplay, guests, stats).

---

## ✦ Version Comparison

| Feature | CSM 1.0 | CSM 2.0 | CSM Jam 3.0 ✦ |
| :--- | :--- | :--- | :--- |
| **Sync Protocol** | Basic Millisecond Offset | 4-Point NTP-Lite with Outlier Rejection & Drift Correction | **NTP-Lite + monotonic session clock + proportional rate correction (±4 %, median-filtered)** |
| **Latency Calibration** | None | Manual calibration knob | **Acoustic chirp auto-calibration (incl. Bluetooth) + infinite ruler** |
| **Track Transitions** | Gap between songs | Gap between songs | **Gapless pre-loaded hand-over + synchronized crossfade (up to 10 s)** |
| **Network** | Shared Wi-Fi | Shared Wi-Fi / phone hotspot | **+ Own offline party WLAN (local-only hotspot) + Wi-Fi binding** |
| **Session Joining** | Manual IP Entry | NFC Quick-Join + Offline QR + UDP Auto-Discovery | **+ Browser / iPhone join without app, with host approval** |
| **Supported Guests** | Android | Android | **Android + iOS + any modern browser** |
| **Song Storage** | Host only | Served from each guest's phone | **Uploaded to the host – survives guests leaving** |
| **Crash Recovery** | None | None | **10-minute session resume (queue, position, approvals, stats)** |
| **Queue Management** | Single Host Queue | Multi-Guest Fair Round-Robin Autoplay Pool | **Round-Robin Pool + remembered autoplay folder** |
| **Voice & Interaction** | Music Only | Live Duplex Push-to-Talk Megafon with Auto-Ducking | **Megafon in app *and* browser** |
| **Party Utilities** | None | Party-Light Strobe + Session Wrapped | **+ Shareable Wrapped image & song list** |
| **UI Experience** | Static Dark UI | Dynamic Palette Gradients + AMOLED Saver | **Full redesign with custom type system** |
| **Late-Join Catch-up** | Manual seek | Deterministic Catch-Up (< 150 ms Δ) | **Catch-up + learned seek compensation** |
| **Codebase** | Single file | Monolithic `MainActivity` | **Modular architecture + JVM unit tests** |

---

## ✦ Architecture Deep Dive

```
 ┌──────────────────────────────────────────────────────────┐
 │                       HOST DEVICE                        │
 │  ┌──────────────────────┐   ┌──────────────────────────┐ │
 │  │  LocalMusicServer    │   │  LocalFileServer         │ │
 │  │  (WebSocket :8887)   │   │  (NanoHTTPD :8081)       │ │
 │  │  queue · clock · PTT │   │  audio · art · web app   │ │
 │  └──────────┬───────────┘   └────────────┬─────────────┘ │
 │  AutoCalibrator · SessionStore · LocalHotspot (optional) │
 └─────────────┼────────────────────────────┼───────────────┘
               │ JSON / PCM Binary          │ Audio · Uploads
        ┌──────┴──────────────┬─────────────┴──────┐
        ▼                     ▼                    ▼
┌───────────────┐     ┌───────────────┐    ┌────────────────┐
│ Android Guest │     │ Android Guest │    │ Browser Guest  │
│ PlaybackEngine│     │ PlaybackEngine│    │ (iPhone / Web) │
└───────────────┘     └───────────────┘    └────────────────┘
```

### 1. NTP-Lite Synchronization Engine
Continuous 4-point timestamp clock offset algorithm:
$$\text{Offset} = \frac{(T_2 - T_1) + (T_3 - T_4)}{2}$$
$$\text{RTT} = (T_4 - T_1) - (T_3 - T_2)$$
- **Outlier Rejection:** Discards RTT samples exceeding $1.8\times$ median.
- **Monotonic Session Clock:** Built on `System.nanoTime()`, so NTP/carrier time updates mid-party never shift scheduled start times.
- **Drift Correction:** Proportional playback-rate adjustment (±4 %) for small drift, hard re-seek with learned seek compensation for large drift.

### 2. Acoustic Auto-Calibration
- Chirps are scheduled at fixed session times; the host records them and cross-correlates against the known sweep, which yields a peak ≈ 0.2 ms wide even in a noisy room.
- Each device's offset is the difference to the host's own chirp → sent back as a per-device latency correction.

### 3. Local Media Streaming
- NanoHTTPD serves audio (MP3, FLAC, OGG, M4A), embedded artwork, guest uploads and the browser web player.

---

## ✦ Getting Started

### Prerequisites
- **Host / Android guests:** Android 8.0 (API 26) or higher.
- **Browser guests:** any modern browser (Safari on iOS, Chrome, Firefox …).
- **Network:** Same Wi-Fi, a phone's mobile hotspot, or the app's own party WLAN — **no internet required**.

### Usage
1. Launch **CSM Jam** on the host device and choose **Session Hosten** (optionally open your own party WLAN).
2. Guests connect using either:
   - **NFC:** Tap against the host's phone.
   - **QR Code:** Scan the host's QR code.
   - **Auto-Discovery:** Select the detected session in the WLAN list.
   - **Browser (no app):** Scan the QR code with the phone camera or open `http://<host-ip>:8081`, enter a name and wait until the host accepts.
3. Run **Auto-Kalibrierung** once so every speaker hits at the same moment.
4. Queue songs from your device or select an **Autoplay-Ordner** for continuous group playback!

### Build from Source
```bash
# Windows
gradlew.bat assembleRelease
# Unit tests
gradlew.bat testDebugUnitTest
```
Requires JDK 17 and the Android SDK (compileSdk 34).

---

## ✦ Privacy & Security
- **100% Local & Isolated:** Zero cloud servers, zero analytics, zero external network requests.
- **Host-Controlled Access:** Browser guests (and optionally app guests) need host approval; rights for Megaphone / Co-DJ are granted per person.
- **Persistable File Permissions:** Uses Android Storage Access Framework with persistable document tree grants.

---

## ✦ License
- **Personal & Non-Commercial Use Only** — see [LICENSE](LICENSE).
- Bundled fonts (Inter, Space Grotesk, JetBrains Mono) are licensed under the SIL Open Font License, see [`app/src/main/assets/licenses`](app/src/main/assets/licenses).
- Designed & Engineered by **[theStxve](https://github.com/theStxve)**.
