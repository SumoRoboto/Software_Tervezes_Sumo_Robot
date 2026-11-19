# SumoRobot Kanban - Feature Breakdown & Development Roadmap

This document contains the step-by-step engineering roadmap and functional task breakdown for the Mini Sumo Robot (SumoRoboto) system, ready for integration into GitHub Projects / Kanban board.

---

## Kanban Board Cards

### 1. Project Architecture and Framework Initialization
* **Status:** Done
* **Category:** Architecture, Foundations
* **Tech Stack:** Kotlin (2.2.10), Jetpack Compose (Material 3), Gradle Kotlin DSL (AGP 9.4.1), Java 21/25 (JBR)
* **Objective:** Establish a modern, 100% Jetpack Compose MVVM architecture following official Android standards.
* **Implementation Details:**
  - Configured modern Gradle environment (minSdk 26, targetSdk 34+).
  - Integrated Jetpack Compose Material 3 UI library and Compose BOM, completely eliminating legacy XML layouts.
  - Implemented Model-View-ViewModel (MVVM) and Unidirectional Data Flow (UDF) patterns driven by reactive Kotlin StateFlows.
  - Set up an isolated multi-module repository structure: Android app (Application), microcontroller firmware (Gravitas), and web platform (TelemetryPlatform).
  - Declared and dynamically handled Android 12+ (API 31+) runtime Bluetooth permissions (BLUETOOTH_CONNECT, BLUETOOTH_SCAN).

---

### 2. Bluetooth Classic (RFCOMM / SPP) Communication Layer
* **Status:** Done
* **Category:** Bluetooth, Hardware I/O
* **Tech Stack:** Kotlin (Coroutines, Dispatchers.IO, Flow)
* **Objective:** Build a reliable, low-latency serial communication pipeline with HC-05 and HC-06 Bluetooth modules.
* **Implementation Details:**
  - Implemented standard Serial Port Profile (SPP) UUID (00001101-0000-1000-8000-00805F9B34FB).
  - Handled byte packet fragmentation: buffered line-delimited serial bytes (\n) using a dedicated background BufferedReader.readLine() coroutine loop to prevent parser exceptions.
  - 40 Hz Rate-Limiting Ticker: implemented a strict 25 ms dispatch loop for motor packets (M,<pwmL>,<pwmR>\n) to prevent microcontroller UART buffer overflow (anti-flooding).
  - Emergency stop command (STOP\n) and operational state switching (MODE,MANUAL\n / MODE,AUTO\n).
  - Virtual Sumo Robot Simulation: built an in-memory simulation engine providing realistic synthetic telemetry (distances, battery voltage, line sensors) and accepting motor commands without physical hardware.

---

### 3. Telemetry Data Model and Battery Monitoring
* **Status:** Done
* **Category:** Telemetry, Power Management
* **Tech Stack:** Kotlin
* **Objective:** Process incoming real-time telemetry packets and monitor battery voltage levels.
* **Implementation Details:**
  - Defined robust TelemetryData and BatteryState immutable data models.
  - Parsed incoming TEL,<distL>,<distR>,<lineL>,<lineR>,<vBat>\n serial frames with strict error handling.
  - Dynamic battery voltage mapping: 8.40V (100%), 7.40V (~50%), and < 6.80V (critical warning threshold to prevent deep discharge).
  - Unit tests (ProtocolParserTest.kt): verified command serialization, voltage computation, and fragmented frame handling under JUnit.

---

### 4. Data Logging and RFC-4180 CSV Export
* **Status:** Done
* **Category:** Data Collection, Export
* **Tech Stack:** Kotlin, Android Storage API, FileProvider
* **Objective:** Record sensor measurements for post-match analysis and debugging.
* **Implementation Details:**
  - Implemented RFC-4180 compliant CSV logging engine (TelemetryCsvLogger.kt).
  - Automatic header generation: timestamp (ms), left/right distance (cm), line sensors, battery voltage (V), motor PWM values.
  - Session lifecycle management: Start/Stop state machine, elapsed stopwatch timer, and live sample counter.
  - Saved files to application-scoped storage (context.getExternalFilesDir()).
  - Android FileProvider and ShareCompat integration: direct file sharing to Google Drive, Gmail, Bluetooth, and local storage.

---

### 5. Lifecycle, Splash Screen and Smart Connection (Onboarding Flow)
* **Status:** Done
* **Category:** Navigation, Lifecycle, UX
* **Tech Stack:** Jetpack Compose, Coroutines
* **Objective:** Create a seamless onboarding and automatic Bluetooth connection experience.
* **Implementation Details:**
  - Full-screen animated Splash Screen with auto-discovery transition.
  - Automatic reconnection logic targeting previously paired Sumo Robot HC-05 devices.
  - Permission rationale dialogs guiding the user through Bluetooth and Nearby Devices grants.
  - Sidedoor / Demo Mode: instant navigation into UI and simulation without requiring physical Bluetooth hardware.

---

### 6. Persistent Top App Bar and Connection Status Sheet
* **Status:** Done
* **Category:** Navigation, UI Components
* **Tech Stack:** Jetpack Compose (Material 3)
* **Objective:** Provide continuous robot status indicators and quick Bluetooth device switching.
* **Implementation Details:**
  - TopSumoAppBar: displays target device name, connection status pill (Connected / Disconnected / Connecting), and live battery widget.
  - ModalBottomSheet device selector (DeviceSelectionSheet.kt) listing bonded devices with single-tap connection.
  - Visual distinction between physical hardware targets and the Virtual Sumo Robot.

---

### 7. Manual Remote Control and Virtual Joystick (Control Screen)
* **Status:** Done
* **Category:** Controls, UI/UX
* **Tech Stack:** Kotlin (Compose Canvas, Gesture Detection)
* **Objective:** Implement a low-latency touch-based differential drive interface.
* **Implementation Details:**
  - Custom Virtual Joystick (VirtualJoystick.kt): 2D Canvas rendering with spring physics, deadzone calibration, and visual bounds.
  - Mathematical differential steering mapper: translates X and Y thumb coordinates into left and right motor PWM values (-255 to +255).
  - Speed Limiter Slider: scales max motor power output from 10% to 100%.
  - Mode Selection Chips: toggle between MANUAL RC and AUTONOMOUS combat strategies.
  - Live Motor PWM Status Cards: real-time speed and direction indicators (FWD / REV / STOP).
  - Ergonomic Emergency Stop Button: prominent bottom-anchored red button sending immediate motor shutdown.

---

### 8. Real-Time Sensor Telemetry (Telemetry Screen)
* **Status:** Done
* **Category:** Telemetry, Sensors
* **Tech Stack:** Jetpack Compose (Material 3)
* **Objective:** Visualize sensor feedback during bench testing and live robot matches.
* **Implementation Details:**
  - Cleaned and streamlined telemetry layout removing unnecessary sensor clutter.
  - Dedicated Battery Card: real-time measured voltage (VBat), estimated battery percentage, and dynamic load-based discharge rate.
  - Unified Session Recording Card: switch between MATCH and FREE ROAM modes, live sample counter, user notes input, and Start / Stop / Discard actions.

---

### 9. Dohyo Arena Map and Canvas Radar Visualization (Dohyo Screen)
* **Status:** Done
* **Category:** Graphics, Canvas, Radar
* **Tech Stack:** Kotlin (Compose Canvas 2D)
* **Objective:** Render the official Mini Sumo Dohyo arena to scale and project opponent distance radar cones.
* **Implementation Details:**
  - Rendered official 77 cm diameter Dohyo black arena with 2.5 cm white border and brown Shikiri-sen starting lines.
  - Centered host robot icon with dynamic orientation vector.
  - Dynamic Radar Cones: projected left and right distance sensor fields of view with real-time ping distance indicators.
  - Border alert highlights: red border LED flash triggers when line sensors detect Dohyo boundaries.

---

### 10. Theme System, Accent Color Picker and Settings (Settings Screen)
* **Status:** Done
* **Category:** Customization, Settings
* **Tech Stack:** Kotlin, SharedPreferences
* **Objective:** Full visual personalization and system configuration hub.
* **Implementation Details:**
  - Theme mode selector: Dark, Light, and System default profiles.
  - Accent Color Palette: 8 selectable primary colors (Cyan, Amber, Emerald, Purple, Pink, Sky Blue, Orange, Lime) dynamically re-theming controls, sliders, and highlights.
  - AppSettingsManager: persistent storage of user preferences and custom robot names in SharedPreferences.
  - Backend Server URL configuration: preset chips for emulator (10.0.2.2:8000), localhost (127.0.0.1:8000), and custom Wi-Fi network endpoints.
  - Hardware specifications card: voltage limits (8.4V max, 6.8V critical cutoff) and UART communication parameters.

---

### 11. Contrast Optimization, Iconography and Asset Pipeline
* **Status:** Done
* **Category:** UI/UX, Assets, Bugfix
* **Tech Stack:** Jetpack Compose, XML Vector Drawables, Android Resource Packaging
* **Objective:** Fix light mode readability defects and generate production-ready application icons.
* **Implementation Details:**
  - Light mode contrast fix: replaced low-contrast containers with explicit slate borders (1.dp BorderStroke) across all cards, buttons, and chips.
  - Production adaptive icon: generated complete density suite (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi) from source assets with adaptive background and foreground vector layers.
  - Dark mode connection screen fix: wrapped root in Material 3 Surface ensuring high-contrast onBackground typography.

---

### 12. Backend Architecture, REST API and SQLite Database
* **Status:** Done
* **Category:** Backend, Database, Networking
* **Tech Stack:** Python 3, Flask, SQLite3, Retrofit2, OkHttp3
* **Objective:** Build a lightweight, persistent telemetry backend with automated emulator reverse port tunneling.
* **Implementation Details:**
  - Relational SQLite schema (telemetry.db): created telemetry_logs, safety_events, sessions, and session_points tables with WAL journal mode and timestamp indexing.
  - REST Endpoints: implemented GET /api/telemetry, GET /api/history, GET/POST /api/sessions, GET /api/sessions/<id>/points, DELETE /api/database/clear, and POST /api/server/shutdown.
  - Background ADB Watchdog: automatic adb reverse tcp:8000 tcp:8000 execution ensuring zero-config connectivity from Android emulators and USB devices.
  - Graceful remote shutdown: added POST /api/server/shutdown allowing clients to remotely terminate the Python process on demand.

---

### 13. Real-Time Telemetry Pipeline and Rule Engine (Hot Path / Cold Path)
* **Status:** Done
* **Category:** Rule Engine, Data Pipeline
* **Tech Stack:** Python, Kotlin
* **Objective:** Process high-frequency telemetry while segregating urgent safety incidents from standard audit records.
* **Implementation Details:**
  - Cold Path: logs periodic robot state frames (battery voltage, motor PWM, sensor distances) directly into telemetry_logs.
  - Hot Path (Rule Engine): instantly evaluates critical conditions (e.g. CRITICAL_BATTERY, OBSTACLE_COLLISION, LINE_EDGE_CROSSING) and writes immediate alerts to safety_events.
  - Dual-stream synchronization: both the Android app and the Web UI consume filtered logs via ?type=cold, ?type=hot, or ?type=all query parameters.

---

### 14. Web Telemetry Dashboard and Live Charting
* **Status:** Done
* **Category:** Web, Frontend, Visualization
* **Tech Stack:** HTML5, Tailwind CSS, Chart.js
* **Objective:** Provide a clean, browser-accessible dashboard for remote telemetry monitoring and database administration.
* **Implementation Details:**
  - Real-time gauge indicators: battery voltage status, motor PWM bar charts, and distance sensor readings.
  - Hot Path Emergency Banner: red pulsing banner triggering upon critical safety events with an operator acknowledgment button.
  - Interactive Session Explorer: tabular view of historical match sessions with search, filter presets, and database purge tools.

---

### 15. 60 FPS Hardware-Accelerated Sumo Mini-Game
* **Status:** Done
* **Category:** Gaming, Canvas, Performance
* **Tech Stack:** Kotlin, Jetpack Compose Canvas, Choreographer Frame Loop
* **Objective:** Deliver a fluid 60 FPS interactive Sumo physics game without frame drops or GC pressure.
* **Implementation Details:**
  - Isolated Frame Tick: scoped frame updates directly inside Compose Canvas to eliminate full-screen recomposition on every Choreographer nano tick.
  - Zero Allocation Render Loop: replaced per-frame Path and List heap instantiations with direct drawLine and drawCircle primitive calls.
  - Throttled HUD Recomposition: integer-quantized score and timer updates reducing text layout passes from 120 Hz to 1 Hz.
  - AI Opponent Logic: state-driven AI behavior with Easy, Medium, and Hard difficulty levels, ring boundary avoidance, and collision physics.

---

### 16. Modern UI/UX Refactoring, Search and In-App Generator Sync
* **Status:** Done
* **Category:** UI/UX, Optimization, Integration
* **Tech Stack:** Jetpack Compose, Coroutines
* **Objective:** Streamline the history and telemetry screens, optimize network timeouts, and unify in-app generation.
* **Implementation Details:**
  - Expandable Search Bar: replaced always-open textfield with an animated icon toggle, saving valuable vertical screen space.
  - Horizontally Scrollable Filter Chips: updated filter chips row (All, Cold Path, Hot Path, Sessions) with horizontal scroll and single-line constraints to eliminate awkward text wrapping.
  - In-App Telemetry Generator: unified generator button (Generate / STOP) with background session recording, local voltage discharge modeling, and automatic server upload.
  - Low-Latency Network Failover: reduced network connect timeout from 15s to 4s with user-friendly error banners guiding users to start the server.
