# Mini Sumo Robot & Mobile Telemetry Platform

> Embedded firmware, modern Android mobile controller (Jetpack Compose), and integrated Web & REST Telemetry Platform with an SQLite database for an autonomous and RC-capable **Mini Sumo Robot**.

---

## Project Overview

The objective of this project is to build a complete software and hardware ecosystem for an autonomous and remote-controlled Mini Sumo Robot:
1. **Microcontroller Firmware**: Real-time sensor acquisition, Dohyo border edge detection, opponent tracking, and motor control.
2. **Android Mobile Application**: Bluetooth Classic (HC-05) remote control, built-in virtual robot simulation, live telemetry streaming, 60 FPS Sumo mini-game, and comprehensive telemetry session analysis with charting.
3. **Telemetry Platform (Backend & Web)**: Dual-stream data pipeline with Hot Path (immediate safety alerts) and Cold Path (normal operational logs) evaluated by an integrated Rule Engine, persisted in SQLite, and visualized on a browser dashboard.

---

## Repository Structure

```text
.
├── Application/        # Android Mobile Application source code (Kotlin, Jetpack Compose, Retrofit)
├── Assets/             # Pre-built, ready-to-install APK binaries (SumoRobot.apk)
├── TelemetryPlatform/  # Web interface and Python REST API server with SQLite database
│   ├── backend/        # mock_server.py and telemetry.db database
│   └── web/            # index.html – Live web telemetry dashboard (Tailwind CSS + Chart.js)
├── Backend/            # Python virtual environment (venv) and requirements.txt dependencies
├── Gravitas/           # Microcontroller firmware (PlatformIO / Arduino Nano C++)
├── KANBAN.md           # Engineering task roadmap and feature breakdown (Kanban board cards)
└── Docs/               # Circuit schematics, specifications, and project documentation
```

---

## Android Mobile Application (`Application/`)

The mobile application is built using modern Android architecture components (**Kotlin**, **Jetpack Compose**, **Material 3**, **MVVM pattern**, and **Coroutines / StateFlow**).

### Core Features:
* **Robot Connection (`ConnectionScreen`)**:
  * Automatic Bluetooth discovery and bonding with HC-05 / HC-06 modules.
  * **Virtual Sumo Robot Mode**: Fully test all app features without physical hardware (includes a realistic motor-load-based battery discharge model).
* **Live Telemetry (`TelemetryScreen`)**:
  * Real-time battery voltage (VBat) monitoring with an estimated charge percentage and dynamic discharge rate indicators.
  * Unified session recorder: toggle between Match (`MATCH`) and Free Roam (`FREE ROAM`) modes, track sample counts, add notes, and save sessions.
* **Data & Session History (`HistoryScreen`)**:
  * Native Compose Canvas **Telemetry Chart**: multi-metric line plots for battery voltage, distance sensors, and motor PWM values.
  * **Hot / Cold Path Filtering**: filter by safety alerts (e.g. collision hazard, edge crossing) or normal operational logs.
  * **Expandable Search Bar**: quickly search logs and rules without wasting vertical screen space.
  * **In-App Data Generator ("Generate" / "STOP")**: locally simulates telemetry matching the rule engine and automatically uploads recorded sessions to the backend database upon completion.
* **Sumo Mini-Game (`SumoMiniGame`)**:
  * Hardware-accelerated, 60 FPS touch-based Dohyo mini-game.
  * State-machine AI opponent with multiple difficulty levels (Easy, Medium, Hard), round scoring, and ring boundary collision physics.
* **Settings (`SettingsScreen`)**:
  * Theme switcher (Dark, Light, System) and custom primary accent color palette.
  * Backend server URL configuration with presets for emulator (`10.0.2.2:8000`), localhost (`127.0.0.1:8000`), and local Wi-Fi networks.

### Installing the App on a Phone:
1. Download the [Assets/SumoRobot.apk](Assets/SumoRobot.apk) package directly onto your Android device.
2. Open the file to install (allow installation from unknown sources if prompted).

---

## Telemetry Platform & Backend (`TelemetryPlatform/`)

The telemetry platform handles data logging, persistence, safety rule evaluation, and real-time visualization for both the physical robot and the mobile app.

### REST API Endpoints (`TelemetryPlatform/backend/mock_server.py`):
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/health` | Health check endpoint (`{"status": "ok"}`) |
| `GET` | `/api/telemetry` | Current live telemetry stream |
| `GET` | `/api/history?type={all\|cold\|hot}` | Filtered telemetry logs and safety alert events |
| `GET` | `/api/sessions` | List of all recorded telemetry sessions |
| `POST` | `/api/sessions` | Upload a new telemetry session with data points |
| `GET` | `/api/sessions/<id>/points` | Retrieve granular telemetry points of a session for charting |
| `POST` | `/api/simulator/start` | Start the server-side simulation engine |
| `POST` | `/api/simulator/stop` | Stop and persist the server simulation session |
| `POST` | `/api/server/shutdown` | Gracefully shut down the server process remotely |
| `DELETE` | `/api/database/clear` | Clear the database and reset auto-increment IDs |

### Starting the Server:
* **Double-click (macOS)**: Run [Start_Sumo_Server.command](Start_Sumo_Server.command) in the project root.
* **Terminal (macOS / Linux)**:
  ```bash
  ./start_server.sh
  ```
  *(The script automatically activates the Python virtual environment, configures `adb reverse tcp:8000 tcp:8000` for emulators, and prints local network IP addresses).*

---

## Robot Hardware & Firmware (`Gravitas/`)

* **Microcontroller**: Arduino Nano (ATmega328P, 16 MHz, 5V).
* **Motor Driver**: TB6612FNG Dual H-Bridge (PWMA: D5, PWMB: D6, STBY: A3).
* **Sensors**:
  * 3× Analog Infrared Distance Sensors (S1: A2, S2: A1, S3: A0).
  * Dohyo White Line Edge Sensors.
* **Communication**: HC-05 / HC-06 Bluetooth Serial Module (UART).
* **Build System**: VS Code + **PlatformIO** (`platform = atmelavr`, `board = nanoatmega328`).

---

## Quick Start Guide

### 1. Running the App (Virtual Mode)
1. Open the `Application/` directory in **Android Studio**, or install `Assets/SumoRobot.apk`.
2. Launch the application.
3. On the connection screen, tap **"Virtual Sumo Robot"**.
4. Test real-time control, live telemetry, and session generation on the **"Telemetry"** and **"Data"** tabs.

### 2. Testing Database Synchronization
1. Start the server on your computer using `./start_server.sh`.
2. In the Android app, go to the **"Data"** tab and tap **"Generate"**. Let it run for a few seconds, then tap **"STOP"**.
3. The new session will immediately sync to the server's SQLite database and appear in the interactive chart.
4. Open [TelemetryPlatform/web/index.html](TelemetryPlatform/web/index.html) in your web browser to view the live web dashboard.

---

## Tech Stack

* **Mobile**: Kotlin, Jetpack Compose, Material 3, Android Architecture Components, Coroutines, StateFlow, Retrofit 2, OkHttp 3.
* **Backend & Database**: Python 3, Flask, Flask-CORS, SQLite3.
* **Web Frontend**: HTML5, Tailwind CSS, Chart.js.
* **Embedded System**: C++, Arduino Framework, PlatformIO, AVR GCC.
