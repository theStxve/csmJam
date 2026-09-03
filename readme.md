# 𓆩🜲𓆪 CSM 2.0: The Next-Gen Offline Music Sync & Party Hub

[![Version](https://img.shields.io/badge/version-2.0.0--nextgen-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/platform-Android-blue.svg)]()
[![Sync Precision](https://img.shields.io/badge/sync%20precision-%C2%B11ms-gold.svg)]()
[![Privacy](https://img.shields.io/badge/privacy-100%25%20Offline%20Local-success.svg)]()

> **CSM 2.0** turns any group of smartphones into a synchronized, distributed surround-sound system and collaborative party console — **100% offline, decentralized, and with microsecond-level synchronization.**

---

## ✦ What's New in CSM 2.0?

CSM 2.0 is a complete evolution from a simple audio streamer into a full-fledged local party ecosystem with real-time audio broadcasting, live analytics, AMOLED optimization, and automated DJ intelligence.

### ◈ Dynamic Album-Art Gradient Theming
- **Palette API Extraction:** Real-time extraction of dominant vibrant and muted tones from every track's album cover.
- **Unified App-Wide Aesthetics:** Beautiful, smoothly transitioning vertical gradients across both tabs, the MiniPlayer, FullScreenPlayer, Dialogs, and the Android System Status Bar.

### ◈ "Session Wrapped" – Live & Departure Analytics
- **Live Session Metrics:** Real-time session runtime, total songs played counter, and live top contributor tracking.
- **Departure Summary Modal:** When leaving a session, review an instant party recap (Session Duration, Total Tracks, Top DJ / Contributor, and Most Played Artist) with the option to stay and keep partying or confirm exit.

### ◈ Decentralized Fair Round-Robin Autoplay Pool
- **Guest-Owned Fallback Libraries:** Every participant (host & guests) can select a local music folder directly from their device.
- **Smart Server Orchestration:** When the queue runs low ($\le 1$ track), the server automatically requests a random track from the next registered participant in a fair round-robin cycle. No more dead silence at parties!

### ◈ Instant NFC Quick-Join & QR Discovery
- **NFC Tap-to-Join:** Simply tap phones together — the host broadcasts an NDEF payload and guests instantly connect without typing IPs.
- **Zero-Config Offline Discovery:** UDP Multicast Beacon + Zero-Config Offline QR Scanner (100% offline via ZXing Embedded).

### ◈ AMOLED Battery Saver Mode
- **Pure Black Optimization:** Dedicated switch in the Session Tab that forces true `#000000` AMOLED blacks, disables costly visual animations, and minimizes CPU load while keeping audio playback, drift correction, and the megaphone alive all night.

### ◈ Real-Time Live Megafon (Push-to-Talk)
- **Low-Latency Voice Streaming:** 16kHz PCM duplex voice streaming over WebSockets with automatic music ducking (music volume lowers to 15% during announcements).
- **Host Permission Controls:** Host can selectively grant or revoke Megaphone and Co-DJ control rights per participant.

### ◈ Party-Light Mode (Flash Strobe Sync)
- **Beat-Synced Flashlight:** Multi-device synchronized camera strobe (Beat-Drop, Fast Strobe, Chill Strobe) hidden behind an interactive Easter Egg.

### ◈ Full Gestural Swipeable Navigation
- **Horizontal Pager Tabs:** Seamless swiping between **Warteschlange** (Queue & PTT) and **Session** (WLAN info, Signal Strength, Autoplay settings, Guest management, Stats).

---

## ✦ Core Highlights & Performance

| Feature | CSM 1.0 | CSM 2.0 ✦ |
| :--- | :--- | :--- |
| **Sync Protocol** | Basic Millisecond Offset | **4-Point NTP-Lite with Outlier Rejection & Drift Correction** |
| **UI Experience** | Static Dark UI | **Dynamic Palette Album-Art Gradients + AMOLED Saver** |
| **Session Joining** | Manual IP Entry | **NFC Quick-Join + Offline QR Code + UDP Auto-Discovery** |
| **Queue Management** | Single Host Queue | **Multi-Guest Fair Round-Robin Autoplay Pool** |
| **Voice & Interaction** | Music Only | **Live Duplex Push-to-Talk Megafon with Auto-Ducking** |
| **Party Utilities** | None | **Party-Light Beat Strobe + Session Wrapped Analytics** |
| **Late-Join Catchup** | Manual seek | **Instant Deterministic Catch-Up ($< 150\text{ms}$ Delta)** |

---

## ✦ Architecture Deep Dive

```
 ┌────────────────────────────────────────────────────────┐
 │                      HOST DEVICE                       │
 │  ┌──────────────────────┐   ┌────────────────────────┐ │
 │  │  LocalMusicServer    │   │  LocalFileServer       │ │
 │  │  (WebSocket :8887)   │   │  (NanoHTTPD :8081)     │ │
 │  └──────────┬───────────┘   └───────────┬────────────┘ │
 └─────────────┼───────────────────────────┼──────────────┘
               │ JSON / PCM Binary         │ Audio Stream
        ┌──────┴──────────────┬────────────┴──────┐
        ▼                     ▼                   ▼
┌──────────────┐      ┌──────────────┐     ┌──────────────┐
│   Guest 1    │      │   Guest 2    │     │   Guest 3    │
│ (Music Client)│     │ (Music Client)│    │ (Music Client)│
└──────────────┘      └──────────────┘     └──────────────┘
```

### 1. 4-Point NTP-Lite Synchronization Engine
CSM 2.0 uses a continuous 4-point timestamp clock offset algorithm:
$$\text{Offset} = \frac{(T_2 - T_1) + (T_3 - T_4)}{2}$$
$$\text{RTT} = (T_4 - T_1) - (T_3 - T_2)$$
- **Outlier Rejection:** Discards RTT samples exceeding $1.8\times$ median.
- **Adaptive Speed Throttling:** Non-intrusive micro-adjustments ($0.98\times$ / $1.02\times$ playback speed) when drift is within $[-20\text{ms}, +20\text{ms}]$; hard-snaps if drift $> 400\text{ms}$.

### 2. Infinite Calibration Knob
- Vertical drag gesture for live millisecond tuning to compensate for Bluetooth speaker buffers and DSP latency.

### 3. Local Media Streaming & NAT Loopback Bypass
- Direct file and artwork delivery via NanoHTTPD HTTP stream engine, supporting MP3, FLAC, OGG, and M4A with ID3/embedded artwork caching.

---

## ✦ Getting Started

### Prerequisites
- **Android:** Android 8.0 (API 26) or higher.
- **Network:** Connected to the same Wi-Fi router or one phone's Mobile Hotspot (no internet connection required).

### Usage
1. Launch **CSM Jam** on the Host device and choose **Session Hosten**.
2. Guests connect using either:
   - **NFC:** Tap against the Host's phone.
   - **QR Code:** Scan the Host's QR code.
   - **Auto-Discovery:** Select the detected session in the WLAN list.
3. Queue songs from your device or select an **Autoplay-Ordner** for continuous group playback!

---

## ✦ Privacy & Security
- **100% Local & Isolated:** Zero cloud servers, zero analytics, zero external network requests.
- **Persistable File Permissions:** Uses Android Storage Access Framework with persistable document tree grants.

---

## ✦ License
- **Personal & Non-Commercial Use Only.**
- Designed & Engineered by **[theStxve](https://github.com/theStxve)**.

