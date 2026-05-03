# 🎵 CSM Jam: The Ultimate WLAN Sync Experience

[![Version](https://img.shields.io/badge/version-1.0.0--stable-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/platform-Android-blue.svg)]()
[![License](https://img.shields.io/badge/license-Personal--Use--Only-orange.svg)]()

**CSM Jam** is a high-fidelity music synchronization engine that turns your group of smartphones into a unified, powerful sound system. Experience perfectly timed audio across all devices in the room.

---

## 💎 Features

### ⚡ Precision Sync Technology
- **Millisecond Accuracy:** Proprietary synchronization protocol using server-client time offset calculation.
- **Drift Correction (Late Catch-Up):** Intelligent algorithm that detects when a device falls behind and performs invisible adjustments to stay in sync.
- **Zero-Latency Broadcast:** Instant state distribution via WebSockets—when the host skips, everyone skips.

### 🎛️ Infinite Calibration Knob
- **Tactile Tuning:** Unique vertical drag gesture to adjust audio offset in real-time.
- **Bluetooth Compensation:** Instantly compensate for the inherent delay of Bluetooth speakers while listening.

---

## 🛠️ Technical Architecture & Deep Dive

CSM Jam is built on a **Deterministic Multi-Client Synchronization Model**.

### 1. Clock Synchronization (NTP-lite)
Guests synchronize their internal "Jam Clock" with the Host's system time:
- **RTT Sampling:** The client sends bursts of timing packets to the Host.
- **Latency Calculation:** `Latency = (T_received - T_sent) / 2`.
- **Offset Adjustment:** `ServerTimeOffset = T_server - (T_local - Latency)`.
- **Drift Management:** The app periodically re-syncs the offset to account for hardware crystal oscillator inaccuracies.

### 2. The Deterministic Playback Window (1000ms Buffer)
To eliminate start-lag, CSM Jam uses a "Future-Start" protocol:
1. The Host broadcasts a `PLAY` command with a timestamp exactly **1000ms** in the future.
2. Every client pre-buffers the track and enters a **High-Priority Busy-Wait Loop**.
3. When `SystemTime + Offset == TargetTime`, the player transitions from pause to play instantly.
This bypasses the inconsistent delay of the Android UI thread.

### 3. Local Audio Streaming & NAT Bypass
The Host acts as a high-performance local media server:
- **NanoHTTPD Engine:** Serves audio chunks via HTTP to guests.
- **Direct Memory Access:** To fix NAT-loopback issues (where a Host cannot request its own public IP), artwork is pulled directly from a memory-mapped `ByteArray` for the Host, while Guests fetch it via the network.

### 4. Smart Drift Correction (Late Catch-Up)
If a device experiences buffering or joins late:
- The app monitors the `ExoPlayer.STATE_READY` event.
- Calculation: `TargetPos = (CurrentTime + Offset) - StartTime`.
- If the sync difference is > **150ms**, a silent `seekTo(TargetPos)` is performed.

---

## 🚀 Installation & Usage

### 1. Requirements
- **Host Device:** Ideally the fastest phone with the music library.
- **Guest Devices:** Any Android phone on the same Wi-Fi (or Host's Hotspot).
- **Network:** Stable local WLAN (No internet required for playback).

### 2. Setup
1. Open CSM Jam on all devices.
2. **Host:** Select "Host Mode".
3. **Guest:** Enter the Host's IP and join the jam.
4. **Music:** Select local files from any device.

---

## 🔒 Security & Privacy
- **Local Network Isolation:** No data ever leaves your Wi-Fi network.
- **No Trackers:** No analytics, no ads.

## 📜 License & Usage
- ✅ Allowed: Personal use, study/modification for learning.
- ❌ Prohibited: Commercial redistribution, selling the app.

---
**Developed by [theStxve](https://github.com/theStxve)**
