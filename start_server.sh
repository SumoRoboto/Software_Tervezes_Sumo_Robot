#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR"

echo "======================================================="
echo "   🤖 SUMO ROBOT TELEMETRIA SZERVER INDÍTÁSA...         "
echo "======================================================="

# 1. Virtuális környezet aktiválása
if [ -f "$DIR/Backend/venv/bin/activate" ]; then
    source "$DIR/Backend/venv/bin/activate"
    PYTHON_CMD="python"
elif command -v python3 &> /dev/null; then
    PYTHON_CMD="python3"
else
    echo "❌ Hiba: Python virtuális környezet nem található!"
    exit 1
fi

# 2. ADB keresése és reverse port forwarding
ADB_BIN="adb"
if ! command -v adb &> /dev/null; then
    if [ -f "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
    fi
fi

if "$ADB_BIN" devices 2>/dev/null | grep -q "device$"; then
    echo "📱 Android eszköz / emulátor észlelve!"
    "$ADB_BIN" reverse tcp:8000 tcp:8000 2>/dev/null && echo "   ✅ ADB reverse tcp:8000 sikeresen aktiválva!" || true
else
    echo "ℹ️  Még nem fut Android emulátor (a szerver a háttérben automatikusan csatlakozik, amint elindul)."
fi

# 3. Helyi Wi-Fi IP cím lekérése fizikai telefonhoz
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "127.0.0.1")

echo "-------------------------------------------------------"
echo "🌐 SZERVER ELÉRHETŐSÉG:"
echo "   • Android Emulátor / Helyi gép: http://127.0.0.1:8000"
echo "   • Valós telefon (Wi-Fi):        http://${LOCAL_IP}:8000"
echo "   • SQLite Adatbázis:             $DIR/src/backend/telemetry.db"
echo "-------------------------------------------------------"
echo "Leállításhoz nyomj [Ctrl + C] billentyűt a terminálban."
echo "======================================================="

exec "$PYTHON_CMD" "$DIR/src/backend/mock_server.py"
