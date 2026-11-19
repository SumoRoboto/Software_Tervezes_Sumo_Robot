#!/usr/bin/env bash
# Sumo Robot Telemetria Szerver - macOS Háttérszolgáltatás kezelő
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
PLIST_LABEL="com.sumorobot.telemetry"
PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"
PYTHON_BIN="$DIR/Backend/venv/bin/python"
SERVER_SCRIPT="$DIR/TelemetryPlatform/backend/mock_server.py"

case "$1" in
    install|start)
        echo "[INFO] Installing and launching background service..."
        mkdir -p "$HOME/Library/LaunchAgents"

        # If already loaded, unload first
        launchctl list 2>/dev/null | grep -q "$PLIST_LABEL" && launchctl unload "$PLIST_PATH" 2>/dev/null || true

        cat <<EOF > "$PLIST_PATH"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${PLIST_LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${PYTHON_BIN}</string>
        <string>${SERVER_SCRIPT}</string>
    </array>
    <key>WorkingDirectory</key>
    <string>${DIR}</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/sumo_telemetry.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/sumo_telemetry.err</string>
</dict>
</plist>
EOF

        launchctl load "$PLIST_PATH"
        sleep 1

        if curl -s http://127.0.0.1:8000/api/health 2>/dev/null | grep -q "ok"; then
            echo "[OK] Server successfully started in background (Port: 8000)."
            echo "   * Starts automatically on login."
            echo "   * Log file: /tmp/sumo_telemetry.log"
        else
            echo "[WARN] Service loaded, but port is not responding yet. Check log: /tmp/sumo_telemetry.err"
        fi
        ;;

    uninstall|stop)
        echo "[INFO] Stopping and removing background service..."
        if [ -f "$PLIST_PATH" ]; then
            launchctl unload "$PLIST_PATH" 2>/dev/null || true
            rm -f "$PLIST_PATH"
        fi
        echo "[OK] Background service stopped and removed."
        ;;

    status)
        echo "[INFO] Server Status:"
        if curl -s http://127.0.0.1:8000/api/health 2>/dev/null | grep -q "ok"; then
            echo "   [ACTIVE] Server is running on http://127.0.0.1:8000"
        else
            echo "   [INACTIVE] Server is currently NOT running."
        fi

        if launchctl list 2>/dev/null | grep -q "$PLIST_LABEL"; then
            echo "   [REGISTERED] macOS LaunchAgent is registered."
        else
            echo "   [UNREGISTERED] macOS LaunchAgent is not registered."
        fi
        ;;

    *)
        echo "Usage: $0 {install|start|status|uninstall|stop}"
        exit 1
        ;;
esac
