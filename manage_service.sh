#!/usr/bin/env bash
# Sumo Robot Telemetria Szerver - macOS Háttérszolgáltatás kezelő
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
PLIST_LABEL="com.sumorobot.telemetry"
PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"
PYTHON_BIN="$DIR/Backend/venv/bin/python"
SERVER_SCRIPT="$DIR/src/backend/mock_server.py"

case "$1" in
    install|start)
        echo "🔧 Háttérszolgáltatás telepítése és indítása..."
        mkdir -p "$HOME/Library/LaunchAgents"

        # Ha már be van töltve, először unload
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
            echo "✅ A szerver sikeresen elindult a háttérben (Port: 8000)!"
            echo "   • Mindig futni fog a háttérben, a Mac indításakor is automatikusan elindul."
            echo "   • Nem kell többet manuálisan terminált nyitnod!"
            echo "   • Naplófájl: /tmp/sumo_telemetry.log"
        else
            echo "⚠️ A szolgáltatás betöltve, de a port ellenőrzése még nem válaszolt. Ellenőrizd a naplót: /tmp/sumo_telemetry.err"
        fi
        ;;

    uninstall|stop)
        echo "🛑 Háttérszolgáltatás leállítása és törlése..."
        if [ -f "$PLIST_PATH" ]; then
            launchctl unload "$PLIST_PATH" 2>/dev/null || true
            rm -f "$PLIST_PATH"
        fi
        echo "✅ A háttérszolgáltatás leállítva és eltávolítva."
        ;;

    status)
        echo "🔍 Szerver állapot:"
        if curl -s http://127.0.0.1:8000/api/health 2>/dev/null | grep -q "ok"; then
            echo "🟢 A szerver AKTÍV és válaszol a http://127.0.0.1:8000 címen."
        else
            echo "🔴 A szerver jelenleg NEM fut."
        fi

        if launchctl list 2>/dev/null | grep -q "$PLIST_LABEL"; then
            echo "📋 macOS LaunchAgent regisztrálva van."
        else
            echo "📋 macOS LaunchAgent nincs regisztrálva."
        fi
        ;;

    *)
        echo "Használat: $0 {install|start|status|uninstall|stop}"
        exit 1
        ;;
esac
