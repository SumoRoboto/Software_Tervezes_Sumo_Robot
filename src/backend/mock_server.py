import io
import os
import random
import sqlite3
import json 
from datetime import datetime
from flask import Flask, Response, jsonify, request
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "telemetry.db")

# Memóriában futó globális szótár a robot állapotának tárolására
robot_state = {
    "mode": "MANUAL",
    "state": "STOP",
    "bat": 8.4,
    "s1": 35,
    "s2": 42,
    "s3": 0,
    "pwmA": 0,
    "pwmB": 0,
}


def calculate_battery_percent(v: float) -> int:
    """
    Pontosan a mobilalkalmazás (BatteryState.kt) számítási logikája:
    - FULL_VOLTAGE = 8.4V (100%)
    - NOMINAL_VOLTAGE = 7.4V (50%)
    - MIN_VOLTAGE = 6.0V (0%)
    """
    full_v = 8.4
    nominal_v = 7.4
    min_v = 6.0
    if v >= full_v:
        return 100
    if v <= min_v:
        return 0
    if v >= nominal_v:
        ratio = (v - nominal_v) / (full_v - nominal_v)
        return max(0, min(100, int(50 + ratio * 50)))
    ratio = (v - min_v) / (nominal_v - min_v)
    return max(0, min(100, int(ratio * 50)))



def init_db():
    """SQLite adatbázis inicializálása (Tanári elvárások + Hot/Cold Path + Mobil App)."""
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()

        
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS system_config (
                config_key TEXT PRIMARY KEY,
                config_value REAL NOT NULL,
                description TEXT
            )
            """
        )

       
        cursor.executemany(
            """
            INSERT OR IGNORE INTO system_config (config_key, config_value, description)
            VALUES (?, ?, ?)
            """,
            [
                ("BATTERY_CRITICAL_MIN", 6.4, "Kritikus akkufeszültség (vészleállás)"),
                ("BATTERY_WARNING_MIN", 6.8, "Alacsony akkufeszültség (figyelmeztetés)"),
                ("OBSTACLE_CRITICAL_CM", 5.0, "Kritikus távolság akadálytól (cm)"),
                ("MOTOR_PWM_MAX", 255.0, "Maximális engedélyezett motor PWM")
            ]
        )

        # 2. Cold Path: Élő telemetriai napló (A tanár kérése: raw JSON mérések)
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS telemetry_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                raw_measurement TEXT NOT NULL, -- Itt tároljuk a teljes JSON csomagot!
                status TEXT NOT NULL DEFAULT 'OK'
            )
            """
        )

        # 3. Hot Path: Biztonsági események (Riasztások)
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS safety_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                severity TEXT NOT NULL,
                rule_name TEXT NOT NULL,
                message TEXT NOT NULL,
                raw_measurement TEXT -- A hiba pillanatában lévő teljes adat JSON formátumban
            )
            """
        )

        # 4. Mobil App: Sessions tábla (Dani eredeti kódja érintetlenül)
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_type TEXT NOT NULL,
                created_at TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                start_vbat REAL NOT NULL,
                end_vbat REAL NOT NULL,
                notes TEXT DEFAULT ''
            )
            """
        )

        # 5. Mobil App: Telemetry points tábla (Dani eredeti kódja érintetlenül)
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS telemetry_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                rel_time_ms INTEGER NOT NULL,
                vbat REAL NOT NULL,
                dist_l INTEGER NOT NULL,
                dist_r INTEGER NOT NULL,
                line_l INTEGER NOT NULL,
                line_r INTEGER NOT NULL,
                pwm_l INTEGER NOT NULL,
                pwm_r INTEGER NOT NULL,
                FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
            )
            """
        )

        conn.commit()


import threading
import time
import json
import random

# Globális változó, ami figyeli, hogy fut-e a szimulátor
simulator_running = False

def check_rules_and_log(measurement):
    """
    Ez a RULE ENGINE! Megkapja a JSON adatot, és eldönti a sorsát.
    """
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    raw_json = json.dumps(measurement) # Szöveggé alakítjuk a JSON-t
    
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        
        # 1. Beolvassuk a határértékeket a config táblából (Data-Driven!)
        cursor.execute("SELECT config_key, config_value FROM system_config")
        config = {row[0]: row[1] for row in cursor.fetchall()}
        
        # 2. Szabályok kiértékelése (Hot Path ellenőrzés)
        is_critical = False
        rule_broken = ""
        message = ""
        
        if measurement["bat"] <= config.get("BATTERY_CRITICAL_MIN", 6.4):
            is_critical = True
            rule_broken = "BATTERY_CRITICAL"
            message = "Kritikus akkufeszültség!"
            
        elif measurement["s1"] <= config.get("OBSTACLE_CRITICAL_CM", 5.0) or \
             measurement["s2"] <= config.get("OBSTACLE_CRITICAL_CM", 5.0):
            is_critical = True
            rule_broken = "OBSTACLE_COLLISION"
            message = "Ütközésveszély! Túl közeli akadály."

        # 3. Mentés az adatbázisba (Routing)
        if is_critical:
            # HOT PATH: Vészhelyzet van! Megy a safety_events táblába.
            cursor.execute(
                """INSERT INTO safety_events (timestamp, severity, rule_name, message, raw_measurement)
                   VALUES (?, ?, ?, ?, ?)""",
                (now, "CRITICAL", rule_broken, message, raw_json)
            )
            # Itt elvileg a robot_state-et át kéne állítani STOP-ra, ha lenne fizikai robot.
        else:
            # COLD PATH: Minden normális, mehet a sima logok közé.
            cursor.execute(
                """INSERT INTO telemetry_logs (timestamp, raw_measurement, status)
                   VALUES (?, ?, ?)""",
                (now, raw_json, "OK")
            )
        conn.commit()


@app.route("/api/telemetry", methods=["GET"])
def get_telemetry():
    """
    Telemetriai végpont (Végleges):
    Csak lekérdezi a jelenlegi állapotot. Hozzáadja a simulator_running flag-et,
    hogy a weboldal tudja, fut-e épp a szimuláció.
    """
    # Lemásoljuk a state-et, hogy ne módosítsuk a globálist, csak kiegészítsük a válaszhoz
    response_data = robot_state.copy()
    response_data["is_simulating"] = simulator_running
    return jsonify(response_data), 200

def simulator_worker():
    """Ez a háttérszál (adatgenerátor), ami másodpercenként fut."""
    global simulator_running, robot_state
    while simulator_running:
        # Generálunk egy "raw_measurement" JSON csomagot
        fake_data = {
            "bat": round(random.uniform(6.0, 8.4), 2),  
            "s1": random.randint(2, 80),               
            "s2": random.randint(2, 80),
            "pwmA": random.randint(0, 255),
            "state": "FORWARD"
        }
        
        # Frissítjük a globális állapotot
        robot_state.update(fake_data)
        
        # Beküldjük a Rule Engine-nek kiértékelésre és mentésre (SQLite-ba)
        check_rules_and_log(fake_data)
        
        time.sleep(1)

@app.route("/api/simulator/start", methods=["POST"])
def start_simulator():
    global simulator_running
    if not simulator_running:
        simulator_running = True
        thread = threading.Thread(target=simulator_worker, daemon=True)
        thread.start()
        return jsonify({"status": "ok", "message": "Szimulátor elindítva."}), 200
    return jsonify({"status": "ok", "message": "Már fut."}), 200


@app.route("/api/simulator/stop", methods=["POST"])
def stop_simulator():
    global simulator_running
    simulator_running = False
    return jsonify({"status": "ok", "message": "Szimulátor leállítva."}), 200


@app.route("/api/database/clear", methods=["DELETE"])
def clear_database():
    """Törli a generált adatokat egyetlen gombnyomásra."""
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM telemetry_logs")
        cursor.execute("DELETE FROM safety_events")
        conn.commit()
    return jsonify({"status": "ok", "message": "Adatbázis (naplók és riasztások) sikeresen törölve."}), 200



@app.route("/api/command", methods=["POST"])
def post_command():
    """
    Irányítási végpont:
    JSON body-ban várja a parancsot (F, B, L, R, S), majd frissíti
    a globális robot_state state, pwmA és pwmB értékeit.
    """
    data = request.get_json(silent=True) or {}
    cmd = (data.get("command") or data.get("cmd") or "").strip().upper()

    command_map = {
        "F": {"state": "FORWARD", "pwmA": 255, "pwmB": 255},
        "B": {"state": "BACKWARD", "pwmA": 200, "pwmB": 200},
        "L": {"state": "LEFT", "pwmA": 90, "pwmB": 220},
        "R": {"state": "RIGHT", "pwmA": 220, "pwmB": 90},
        "S": {"state": "STOP", "pwmA": 0, "pwmB": 0},
    }

    if "mode" in data and data["mode"] in ("MANUAL", "AUTO"):
        robot_state["mode"] = data["mode"]
        if not cmd:
            return jsonify({"status": "ok", "robot_state": robot_state}), 200

    if cmd not in command_map:
        return (
            jsonify(
                {
                    "status": "error",
                    "message": "Érvénytelen parancs. Engedélyezett értékek: F, B, L, R, S",
                }
            ),
            400,
        )

    robot_state.update(command_map[cmd])

    return jsonify({"status": "ok", "command": cmd, "robot_state": robot_state}), 200



# Adatbázis inicializálása induláskor
init_db()

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8000, debug=True)
