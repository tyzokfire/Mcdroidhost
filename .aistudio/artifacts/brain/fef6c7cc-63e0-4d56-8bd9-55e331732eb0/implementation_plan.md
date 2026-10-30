# MC-Droid Host: Dedicated Minecraft Java Server for Android

MC-Droid Host turns modern ARM64 Android devices (such as the Xiaomi POCO X6 5G) into a high-performance, 24/7 dedicated Minecraft Java Edition (PC/Mac/Linux) server, featuring automated Playit.gg WAN tunneling, a secure embedded Ktor web administration dashboard, dynamic PaperMC version downloads, thermal hardware protection, and isolated multi-dimension world backups.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The following core user preferences and engineering choices were confirmed during initial planning:

- **Minecraft Java Engine & Default Version**: PaperMC **1.20.4** pre-selected for optimal performance and mod/plugin compatibility on ARM64, with live querying of PaperMC API for other builds/versions.
- **WAN Tunneling (Playit.gg)**: Automated startup alongside the Minecraft server process, with real-time claim code extraction, public domain discovery (`*.gl.joinmc.link`), and connection retry loop.
- **Web Dashboard Security**: Prompting the administrator to set their custom master password on initial setup, secured via cryptographically generated session tokens, brute-force protection, and strict path-traversal sandboxing.
- **Android Runtime Execution (W^X / noexec bypass)**: Execution of `java` binaries and `playit` strictly from private internal storage (`/data/user/0/.../files/runtime/bin/` with `chmod 755`), while server world/config/plugin data resides in `/sdcard/MinecraftServer/`.

---

## 1. Overview & Core Concept

- **What It Does**: Provides a dedicated, battery- and thermal-aware host environment for Minecraft Java Edition directly on Android. Runs an ARM64-optimized OpenJDK 21 Bionic runtime, monitors server performance (RAM, CPU, TPS, connected players), handles automated WAN tunneling with zero router port forwarding via Playit.gg, and serves a full-featured browser dashboard on the local network (port 8080).
- **Target Audience & Hardware**: Mobile power users, homelab enthusiasts, and Minecraft community leaders running on Android 14+ (HyperOS / Xiaomi POCO X6 5G with Snapdragon 7s Gen 2 and up to 12 GB RAM).
- **Key Value**: Delivers zero-lag Java Edition multiplayer hosting on a phone that stays in your pocket, with automated crash recovery, screen-off persistence (WakeLock + WifiLock), and emergency thermal throttling protection.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Initial Setup Flow**:
   - Runtime integrity check: checks for OpenJDK 21 ARM64 Bionic binaries in internal files. If missing, guides 1-tap download with resumption (HTTP Range headers) and SHA-256 checksum verification.
   - Storage permission: verifies `MANAGE_EXTERNAL_STORAGE` and directs user to system settings if needed to establish `/sdcard/MinecraftServer`.
   - Admin credential setup: modal prompts user to define the Web Dashboard password.
2. **Server Launch & Live Monitoring Flow**:
   - User chooses RAM allocation slider (default 4 GB, recommended max 6 GB, safe warning on 6–8 GB).
   - Taps "Start Server" (status changes from `STOPPED` -> `STARTING` -> `RUNNING`).
   - Playit.gg tunnel spins up; app parses claim token or public join address (`mc.gl.joinmc.link`) and displays clickable link + 1-tap copy button.
   - Live console displays colored server log output (`stdout`/`stderr`), player join/leave notices, and interactive command bar (`op`, `gamemode`, `stop`).
3. **Web Dashboard Experience**:
   - Accessible via LAN browser at `http://[Phone-IP]:8080/`.
   - Login page -> Overview telemetry (live RAM/CPU/Thermal gauges) -> Real-time WebSocket terminal -> File manager for `server.properties` and plugins -> Backup snapshot manager.
4. **Thermal Throttling & Power Flow**:
   - Real-time battery temperature and Android PowerManager thermal status indicator (Green <38°C, Orange 38–42°C, Red >=43°C).
   - Emergency auto-shutdown if 45°C persists over 3 minutes with automated world flush (`save-all`).

### Visual Identity & Theme (Jetpack Compose M3)

- **Aesthetic Direction**: High-tech cyber-industrial / server rack aesthetic with dark theme by default, high-contrast status pills, emerald green connection indicators, and deep charcoal background surfaces.
- **Color Palette**:
  - `Background`: Deep Obsidian (`#0F1115`)
  - `Surface`: Slate Card (`#1A1D24`)
  - `Primary / Accent`: Emerald Green (`#10B981`) for running status, server online, and primary actions
  - `Secondary / Warning`: Amber Gold (`#F59E0B`) for elevated thermal status (38–42°C) and claim links
  - `Error / Critical`: Crimson Red (`#EF4444`) for critical thermal status (>=43°C) and server stop
  - `Console Background`: Pure Midnight Black (`#0A0C0E`) with monospace green/cyan/yellow ANSI styling
- **Typography**: Clean monospace typeface for console logs, ports, and IP tokens, paired with modern sans-serif headings and body text.
- **Component Styling**:
  - Tactile server control cards with status badges and animated pulse dots.
  - Interactive RAM allocation slider with clear safe/warning threshold indicators.
  - Quick-action notification bar for background management when the screen is off.

---

## 3. Key Product Decisions & Trade-Offs

1. **Dual Storage Architecture (Internal Binaries vs. Shared World Data)**:
   - *Chosen Approach*: Store OpenJDK binaries (`java`, `lib/`, `libjvm.so`) and `playit` in `context.filesDir/runtime/bin/` with `chmod 755`. Set the Java process working directory to `/sdcard/MinecraftServer/`.
   - *Why*: Android 10+ mounts `/sdcard/` with `noexec`, preventing direct execution of native binaries from external storage. Private app storage allows execution, while `/sdcard/MinecraftServer/` allows the user to browse, drag-and-drop worlds, and install plugins via USB cable on a PC.
2. **Embedded Ktor Web Server (Engine CIO)**:
   - *Chosen Approach*: Ktor CIO lightweight asynchronous server on port 8080, serving a responsive SPA from Android assets, REST APIs for control/files/backups, and WebSockets for real-time console streaming.
   - *Why*: Provides desktop-class server administration from any PC/laptop browser on the local Wi-Fi without having to squint at a phone screen.
3. **Safe World Backup with IO Suspend**:
   - *Chosen Approach*: Before triggering ZIP compression on `world/`, `world_nether/`, and `world_the_end/`, send `save-off\n` and `save-all\n` to stdin, wait for disk flush confirmation, archive, and send `save-on\n`.
   - *Why*: Eliminates chunk corruption caused by archiving while Minecraft is actively modifying LevelDB / Anvil region files. Includes Zip-Slip vulnerability prevention on restore.
4. **Resilience & HyperOS Background Retention**:
   - *Chosen Approach*: Permanent Foreground Service with `PARTIAL_WAKE_LOCK`, `WIFI_MODE_FULL_HIGH_PERF`, and an interactive status notification. Auto-restart loop with 3-attempt limit and 30s backoff for crash recovery.

---

## 4. Technical Architecture & Data Strategy

### System Component Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Android Application                             │
│                                                                        │
│  ┌─────────────────────────┐         ┌──────────────────────────────┐  │
│  │   Jetpack Compose UI    │         │  Embedded Ktor Web Server    │  │
│  │   - Dashboard / Controls│         │  (Port 8080 - LAN Only)      │  │
│  │   - Console / Logs      │         │  - REST API (Auth, Files)    │  │
│  │   - Thermal & Telemetry │         │  - WebSocket (Log Stream)    │  │
│  │   - Backup & Settings   │         │  - Single Page App (Assets)  │  │
│  └───────────┬─────────────┘         └──────────────┬───────────────┘  │
│              │                                      │                  │
│              ▼                                      ▼                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                     MinecraftHostService                         │  │
│  │  - Foreground Service + Ongoing Interactive Notification         │  │
│  │  - PowerManager WakeLock + WifiManager WifiLock                  │  │
│  │  - ThermalMonitor (Battery Temp + PowerManager Thermal Status)   │  │
│  └──────────────────────────┬───────────────────────────────────────┘  │
│                             │                                          │
│      ┌──────────────────────┴──────────────────────┐                   │
│      ▼                                             ▼                   │
│ ┌─────────────────────────┐               ┌──────────────────────────┐ │
│ │  MinecraftServerManager │               │   PlayitTunnelManager    │ │
│ │  - OpenJDK 21 Process   │               │   - playit daemon        │ │
│ │  - Stdin/Stdout pipes   │               │   - Claim token parser   │ │
│ │  - PaperMC engine JAR   │               │   - Public host resolver │ │
│ └────────────┬────────────┘               └─────────────┬────────────┘ │
└──────────────┼──────────────────────────────────────────┼──────────────┘
               ▼                                          ▼
┌───────────────────────────────┐           ┌────────────────────────────┐
│      Private Storage          │           │       Shared Storage       │
│  context.filesDir/runtime/bin │           │   /sdcard/MinecraftServer  │
│  - java (chmod 755)           │           │   - server.properties      │
│  - playit-linux-aarch64       │           │   - world/, plugins/       │
│  - lib/ (Bionic ARM64 JVM)    │           │   - backups/, logs/        │
└───────────────────────────────┘           └────────────────────────────┘
```

### Module Breakdown

1. **`core/runtime/JdkManager.kt`**:
   - Manages OpenJDK 21 ARM64 Bionic archive downloading, SHA-256 validation, extraction into `filesDir/runtime/`, and execution validation via `java -version`.
2. **`core/server/MinecraftServerManager.kt`**:
   - Spawns and manages the Java process targeting `/sdcard/MinecraftServer/`.
   - Applies Aikar JVM flags tuned for ARM64 and configured RAM limits.
   - Handles standard I/O streaming, command injection, and crash detection.
3. **`core/network/PlayitTunnelManager.kt`**:
   - Extracts and runs the official `playit-linux-aarch64` binary.
   - Continuously regex-parses stdout for claim URL (`https://playit.gg/claim/...`) and assigned domain (`*.gl.joinmc.link`).
4. **`core/backup/BackupManager.kt`**:
   - Coordinates world backup snapshots with `save-off` / `save-all` / `save-on` commands.
   - Manages storage quota (FIFO cleanup) and protects against Zip-Slip on restore.
5. **`core/thermal/ThermalMonitor.kt`**:
   - Monitors battery temperature (`BatteryManager.EXTRA_TEMPERATURE`) and thermal status (`PowerManager.getCurrentThermalStatus()`).
   - Provides state flows for UI alerts (Green <38°C, Orange 38–42°C, Red >=43°C) and automated safety shutdown if 45°C persists.
6. **`server/web/KtorWebServer.kt`**:
   - Ktor CIO engine hosting REST routes (`/api/status`, `/api/control`, `/api/files`, `/api/backup`, `/api/auth`) and WebSocket `/ws/console`.
   - Protects against path traversal by validating canonical paths against `/sdcard/MinecraftServer`.
7. **`assets/web/index.html`**:
   - Responsive Single Page Application with embedded CSS/JS, real-time WebSocket console, system charts, file editor, and backup triggers.
8. **`service/MinecraftHostService.kt`**:
   - Central foreground service coordinating process lifetimes, WakeLocks, WifiLocks, and notification actions (`STOP`, `RESTART`).
9. **`ui/MainActivity.kt` & Compose Screens**:
   - Rich native UI with RAM slider, thermal telemetry, IP/Playit claim buttons, version switcher, and terminal tab.

---

## 5. Verification & Testing Strategy

- Build and compilation validation using `compile_applet`.
- Verification of permissions in `AndroidManifest.xml` (`FOREGROUND_SERVICE`, `WAKE_LOCK`, `INTERNET`, `ACCESS_WIFI_STATE`, `MANAGE_EXTERNAL_STORAGE`).
- Unit/Robolectric test validation for command parsing, thermal threshold evaluation, and backup path verification.
