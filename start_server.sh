#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "======================================================="
echo "   SUMO ROBOT TELEMETRY SERVER STARTUP                 "
echo "======================================================="

# 1. Virtual environment activation
if [ -f "$DIR/Backend/venv/bin/activate" ]; then
    source "$DIR/Backend/venv/bin/activate"
    PYTHON_CMD="python"
elif command -v python3 &> /dev/null; then
    PYTHON_CMD="python3"
else
    echo "[ERROR] Python virtual environment not found!"
    exit 1
fi

# 2. ADB detection and reverse port forwarding
ADB_BIN="adb"
if ! command -v adb &> /dev/null; then
    if [ -f "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
    fi
fi

if "$ADB_BIN" devices 2>/dev/null | grep -q "device$"; then
    echo "[INFO] Android device / emulator detected."
    "$ADB_BIN" reverse tcp:8000 tcp:8000 2>/dev/null && echo "   [OK] ADB reverse tcp:8000 successfully active." || true
else
    echo "[INFO] No active emulator detected (watchdog will forward once started)."
fi

# 3. Local Wi-Fi IP address resolution
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")

echo "-------------------------------------------------------"
echo "SERVER ENDPOINTS:"
echo "   * Android Emulator / Local:    http://127.0.0.1:8000"
echo "   * Physical Phone (Wi-Fi):      http://${LOCAL_IP}:8000"
echo "   * SQLite Database:             $DIR/TelemetryPlatform/backend/telemetry.db"
echo "-------------------------------------------------------"
echo "To terminate the server, press [Ctrl + C]."
echo "======================================================="

exec "$PYTHON_CMD" "$DIR/TelemetryPlatform/backend/mock_server.py"
