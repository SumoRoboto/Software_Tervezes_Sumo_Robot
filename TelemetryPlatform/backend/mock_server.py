import io
import json
import os
import random
import sqlite3
import subprocess
import threading
import time
from datetime import datetime
from flask import Flask, Response, jsonify, request
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "telemetry.db")

# In-memory global dictionary to store the current robot state
robot_state = {
    "mode": "MANUAL",
    "state": "STOP",
    "bat": 8.4,
    "s1": 35,
    "s2": 42,
    "s3": 0,
    "pwmA": 0,
    "pwmB": 0,
    "last_alert": None,
    "last_message": None,
    "path": "COLD",
}

# Simulator state and synchronization locks
sim_lock = threading.Lock()
simulator_running = False
current_sim_session_id = None
current_sim_start_time = None
last_sim_step_time = 0.0
sim_battery_ocv = 8.35
combat_ticks = 0


def calculate_battery_percent(v: float) -> int:
    """
    Battery percentage calculation matching the mobile application (BatteryState.kt):
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
    """Initialize SQLite database tables (System Config, Hot/Cold Path, and Mobile App tables)."""
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()

        # 1. System configuration table for rule thresholds (Data-Driven)
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
                ("MOTOR_PWM_MAX", 255.0, "Maximális engedélyezett motor PWM"),
            ],
        )

        # 2. Cold Path: Live telemetry log table (stores raw JSON measurements)
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS telemetry_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                raw_measurement TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'OK'
            )
            """
        )

        # 3. Hot Path: Safety events table (stores critical rule violations and alerts)
        cursor.execute(
            """
            CREATE TABLE IF NOT EXISTS safety_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                severity TEXT NOT NULL,
                rule_name TEXT NOT NULL,
                message TEXT NOT NULL,
                raw_measurement TEXT
            )
            """
        )

        # 4. Mobile App: Sessions summary table
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

        # 5. Mobile App: Session telemetry points table
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


def evaluate_and_route(cursor, measurement, timestamp_str, prefix=""):
    """
    Centralized Data-Driven Rule Engine:
    Reads thresholds from system_config and routes the measurement exclusively
    to either safety_events (Hot Path) or telemetry_logs (Cold Path).
    """
    cursor.execute("SELECT config_key, config_value FROM system_config")
    config = {row[0]: row[1] for row in cursor.fetchall()}

    bat_crit = config.get("BATTERY_CRITICAL_MIN", 6.4)
    obs_crit = config.get("OBSTACLE_CRITICAL_CM", 5.0)

    is_critical = False
    rule_broken = None
    message = "OK"

    if measurement["bat"] <= bat_crit:
        is_critical = True
        rule_broken = "BATTERY_CRITICAL"
        message = f"{prefix}Kritikus akkufeszültség ({measurement['bat']}V)!"
    elif measurement["s1"] <= obs_crit or measurement["s2"] <= obs_crit:
        is_critical = True
        rule_broken = "OBSTACLE_COLLISION"
        message = f"{prefix}Ütközésveszély! Túl közeli akadály ({min(measurement['s1'], measurement['s2'])}cm)."

    raw_json = json.dumps(measurement)

    if is_critical:
        cursor.execute(
            """INSERT INTO safety_events (timestamp, severity, rule_name, message, raw_measurement)
               VALUES (?, ?, ?, ?, ?)""",
            (timestamp_str, "CRITICAL", rule_broken, message, raw_json),
        )
        return "HOT", rule_broken, message
    else:
        cursor.execute(
            """INSERT INTO telemetry_logs (timestamp, raw_measurement, status)
               VALUES (?, ?, ?)""",
            (timestamp_str, raw_json, "OK"),
        )
        return "COLD", None, None


def generate_simulation_step():
    """
    Generates exactly one simulated telemetry sample, runs it through the Rule Engine,
    and saves it to both Hot/Cold path tables and the active session's telemetry_points.
    """
    global robot_state, sim_battery_ocv, combat_ticks, last_sim_step_time

    with sim_lock:
        if not simulator_running or current_sim_session_id is None:
            return

        last_sim_step_time = time.time()
        now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        rel_ms = int((last_sim_step_time - current_sim_start_time) * 1000)

        # Simulation state machine (Combat vs Roam vs Idle)
        if combat_ticks > 0:
            combat_ticks -= 1
            s1 = random.randint(2, 12)
            s2 = random.randint(2, 12)
            pwma = random.randint(210, 255)
            pwmb = random.randint(210, 255)
            state_label = "COMBAT_PUSH"
        else:
            if random.random() < 0.30:
                combat_ticks = random.randint(2, 5)
            is_idle = random.random() < 0.20
            if is_idle:
                pwma, pwmb = 0, 0
                s1 = random.randint(35, 65)
                s2 = random.randint(35, 65)
                state_label = "IDLE_SCAN"
            else:
                pwma = random.randint(90, 140)
                pwmb = random.randint(90, 140)
                s1 = random.randint(15, 60)
                s2 = random.randint(15, 60)
                state_label = "ROAMING"

        # Battery discharge + realistic load drop (occasionally dipping <= 6.4V in heavy combat)
        drain = 0.04 if state_label == "COMBAT_PUSH" else 0.01
        sim_battery_ocv = max(6.5, sim_battery_ocv - drain)
        if state_label == "COMBAT_PUSH" and random.random() < 0.35:
            # Heavy current draw causes temporary voltage sag below critical threshold
            bat = round(random.uniform(6.1, 6.4), 2)
        else:
            bat = round(sim_battery_ocv - random.uniform(0.02, 0.15), 2)

        fake_data = {
            "bat": bat,
            "s1": s1,
            "s2": s2,
            "pwmA": pwma,
            "pwmB": pwmb,
            "state": state_label,
        }

        with sqlite3.connect(DB_PATH) as conn:
            cursor = conn.cursor()

            # 1. Route to Hot or Cold Path via Data-Driven Rule Engine
            path, rule_broken, message = evaluate_and_route(cursor, fake_data, now_str)

            # 2. Record point in telemetry_points for the active session
            cursor.execute(
                """
                INSERT INTO telemetry_points (session_id, rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?)
                """,
                (current_sim_session_id, rel_ms, bat, s1, s2, pwma, pwmb),
            )
            cursor.execute(
                """
                UPDATE sessions SET duration_ms = ?, end_vbat = ? WHERE id = ?
                """,
                (rel_ms, bat, current_sim_session_id),
            )
            conn.commit()

        robot_state.update(fake_data)
        robot_state["path"] = path
        robot_state["last_alert"] = rule_broken
        robot_state["last_message"] = message


def simulator_fallback_worker():
    """
    Fallback background thread: Only generates steps if no Web UI client is actively
    polling GET /api/telemetry (e.g., when controlled solely from the Android App).
    """
    while simulator_running and current_sim_session_id is not None:
        time.sleep(1.0)
        if not simulator_running or current_sim_session_id is None:
            break
        # If no web client polled /api/telemetry in the last 1.3 seconds, step automatically
        if time.time() - last_sim_step_time >= 1.3:
            generate_simulation_step()


@app.route("/api/health", methods=["GET"])
@app.route("/", methods=["GET"])
def health_check():
    return jsonify({"status": "ok", "service": "sumo_telemetry_server"})


@app.route("/api/telemetry", methods=["GET"])
def get_telemetry():
    """
    Live telemetry endpoint:
    When simulator_running is True, generates 1 synchronized step per poll so
    the Web UI and SQLite database maintain a strict 1:1 relationship.
    """
    if simulator_running:
        generate_simulation_step()

    response_data = robot_state.copy()
    response_data["is_simulating"] = simulator_running
    return jsonify(response_data), 200


@app.route("/api/simulator/start", methods=["POST"])
def start_simulator():
    """Starts simulated telemetry generation and creates a new session in SQLite."""
    global simulator_running, current_sim_session_id, current_sim_start_time, sim_battery_ocv, last_sim_step_time, combat_ticks
    if not simulator_running:
        simulator_running = True
        sim_battery_ocv = 8.35
        combat_ticks = 0
        current_sim_start_time = time.time()
        last_sim_step_time = time.time()
        now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        with sqlite3.connect(DB_PATH) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                INSERT INTO sessions (session_type, created_at, duration_ms, start_vbat, end_vbat, notes)
                VALUES (?, ?, 0, 8.4, 8.4, 'Szimulált Menet (MATCH)')
                """,
                ("MATCH", now_str),
            )
            current_sim_session_id = cursor.lastrowid
            conn.commit()

        threading.Thread(target=simulator_fallback_worker, daemon=True).start()
        return (
            jsonify(
                {
                    "status": "ok",
                    "session_id": current_sim_session_id,
                    "message": f"Szimulátor elindítva (Új Session #{current_sim_session_id}).",
                }
            ),
            200,
        )
    return jsonify({"status": "ok", "session_id": current_sim_session_id, "message": "Már fut."}), 200


@app.route("/api/simulator/stop", methods=["POST"])
def stop_simulator():
    """Stops telemetry generation and finalizes the current session in SQLite."""
    global simulator_running, current_sim_session_id, current_sim_start_time
    saved_id = current_sim_session_id
    simulator_running = False

    if current_sim_session_id and current_sim_start_time:
        duration_ms = int((time.time() - current_sim_start_time) * 1000)
        try:
            with sqlite3.connect(DB_PATH) as conn:
                cursor = conn.cursor()
                cursor.execute(
                    "UPDATE sessions SET duration_ms = ?, end_vbat = ? WHERE id = ?",
                    (duration_ms, robot_state.get("bat", 8.4), current_sim_session_id),
                )
                conn.commit()
        except Exception as e:
            print(f"[Simulator Stop Error] {e}")

    current_sim_session_id = None
    return (
        jsonify(
            {
                "status": "ok",
                "message": f"Szimulátor leállítva (Session #{saved_id} mentve az adatbázisba).",
            }
        ),
        200,
    )


@app.route("/api/server/shutdown", methods=["POST"])
def shutdown_server():
    """Remotely stops the Python server process from the client."""
    def delayed_exit():
        time.sleep(0.5)
        os._exit(0)

    threading.Thread(target=delayed_exit, daemon=True).start()
    return jsonify({"status": "ok", "message": "A szerver sikeresen leállt."}), 200


@app.route("/api/database/clear", methods=["DELETE"])
def clear_database():
    """Clear all generated telemetry logs, safety events, and sessions, resetting auto-increment IDs."""
    global simulator_running, current_sim_session_id
    simulator_running = False
    current_sim_session_id = None
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM telemetry_logs")
        cursor.execute("DELETE FROM safety_events")
        cursor.execute("DELETE FROM telemetry_points")
        cursor.execute("DELETE FROM sessions")
        cursor.execute(
            "DELETE FROM sqlite_sequence WHERE name IN ('telemetry_logs', 'safety_events', 'sessions', 'telemetry_points')"
        )
        conn.commit()
    return (
        jsonify(
            {
                "status": "ok",
                "message": "Adatbázis (naplók, riasztások és munkamenetek) sikeresen törölve.",
            }
        ),
        200,
    )


@app.route("/api/command", methods=["POST"])
def post_command():
    """
    Robot control endpoint:
    Expects a command (F, B, L, R, S) in the JSON body and updates
    the global robot_state (state, pwmA, pwmB).
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


@app.route("/api/history", methods=["GET"])
def get_history():
    """
    History and filter endpoint:
    Can be filtered via URL query parameter:
    ?type=cold (normal logs), ?type=hot (alerts), or ?type=all (both).
    Optional search keyword filter: ?q=query
    """
    req_type = request.args.get("type", "all").lower()
    q = (request.args.get("q") or "").strip().lower()
    results = []

    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()

        if req_type in ("hot", "all"):
            rows = cursor.execute(
                "SELECT timestamp, rule_name, message, raw_measurement FROM safety_events ORDER BY id DESC LIMIT 100"
            ).fetchall()
            for row in rows:
                meas = json.loads(row[3]) if row[3] else {}
                item = {
                    "type": "HOT",
                    "timestamp": row[0],
                    "rule_broken": row[1],
                    "message": row[2],
                    "data": meas,
                }
                if not q or q in item["rule_broken"].lower() or q in item["message"].lower() or q in json.dumps(meas).lower():
                    results.append(item)

        if req_type in ("cold", "all"):
            rows = cursor.execute(
                "SELECT timestamp, raw_measurement, status FROM telemetry_logs ORDER BY id DESC LIMIT 100"
            ).fetchall()
            for row in rows:
                meas = json.loads(row[1]) if row[1] else {}
                item = {
                    "type": "COLD",
                    "timestamp": row[0],
                    "rule_broken": None,
                    "message": "OK",
                    "data": meas,
                }
                if not q or q in json.dumps(meas).lower() or q in item["timestamp"].lower():
                    results.append(item)

        if req_type == "all":
            results.sort(key=lambda x: x["timestamp"], reverse=True)

    return jsonify({"items": results[:100]}), 200


@app.route("/api/sessions", methods=["POST"])
def upload_session():
    """
    Receives session telemetry upload from Android app or web client.
    Persists session summary in 'sessions', raw points in 'telemetry_points',
    and routes each point through the Data-Driven Rule Engine (evaluate_and_route).
    """
    data = request.get_json(silent=True) or {}
    session_type = data.get("session_type", "FREE_ROAM")
    created_at = data.get("created_at") or datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    duration_ms = int(data.get("duration_ms", 0))
    start_vbat = float(data.get("start_vbat", 8.4))
    end_vbat = float(data.get("end_vbat", 8.4))
    notes = data.get("notes", "")
    points = data.get("points", [])

    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO sessions (session_type, created_at, duration_ms, start_vbat, end_vbat, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (session_type, created_at, duration_ms, start_vbat, end_vbat, notes),
        )
        session_id = cursor.lastrowid

        point_records = []
        for p in points:
            rel_time = int(p.get("rel_time_ms", 0))
            vbat = float(p.get("vbat", 8.4))
            dist_l = int(p.get("dist_l", 50))
            dist_r = int(p.get("dist_r", 50))
            line_l = int(p.get("line_l", 0))
            line_r = int(p.get("line_r", 0))
            pwm_l = int(p.get("pwm_l", 0))
            pwm_r = int(p.get("pwm_r", 0))
            point_records.append((session_id, rel_time, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r))

            # Route each mobile measurement through the unified Data-Driven Rule Engine
            meas = {
                "bat": vbat,
                "s1": dist_l,
                "s2": dist_r,
                "pwmA": pwm_l,
                "pwmB": pwm_r,
                "state": session_type,
            }
            evaluate_and_route(cursor, meas, created_at, prefix=f"Session #{session_id}: ")

        if point_records:
            cursor.executemany(
                """
                INSERT INTO telemetry_points (session_id, rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                point_records,
            )

        conn.commit()

    return jsonify({
        "status": "success",
        "session_id": session_id,
        "saved_points": len(points),
        "message": f"Munkamenet #{session_id} sikeresen mentve ({len(points)} pont)."
    }), 201


@app.route("/api/sessions", methods=["GET"])
def get_sessions():
    """Returns a summary list of all recorded sessions."""
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        rows = cursor.execute(
            """
            SELECT s.id, s.session_type, s.created_at, s.duration_ms, s.start_vbat, s.end_vbat, s.notes,
                   COUNT(p.id) as point_count
            FROM sessions s
            LEFT JOIN telemetry_points p ON s.id = p.session_id
            GROUP BY s.id
            ORDER BY s.id DESC
            """
        ).fetchall()

        results = []
        for r in rows:
            results.append({
                "id": r[0],
                "session_type": r[1],
                "created_at": r[2],
                "duration_ms": r[3],
                "start_vbat": r[4],
                "end_vbat": r[5],
                "notes": r[6] or "",
                "point_count": r[7]
            })
    return jsonify(results), 200


@app.route("/api/sessions/<int:session_id>/points", methods=["GET"])
def get_session_points(session_id):
    """Returns all telemetry points for a given session."""
    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()
        rows = cursor.execute(
            """
            SELECT rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r
            FROM telemetry_points
            WHERE session_id = ?
            ORDER BY rel_time_ms ASC
            """,
            (session_id,)
        ).fetchall()

        points = []
        for r in rows:
            points.append({
                "rel_time_ms": r[0],
                "vbat": r[1],
                "dist_l": r[2],
                "dist_r": r[3],
                "line_l": r[4],
                "line_r": r[5],
                "pwm_l": r[6],
                "pwm_r": r[7]
            })
    return jsonify({"session_id": session_id, "points": points}), 200


# Initialize the database on server startup
init_db()


def setup_adb_reverse():
    """Configures ADB reverse tcp:8000 for any connected Android emulator or phone."""
    adb_candidates = [
        "adb",
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    ]
    for adb_bin in adb_candidates:
        try:
            res = subprocess.run(
                [adb_bin, "reverse", "tcp:8000", "tcp:8000"],
                capture_output=True,
                text=True,
                timeout=2,
            )
            if res.returncode == 0:
                return True
        except Exception:
            pass
    return False


def setup_adb_reverse_loop():
    """Background watchdog that maintains ADB reverse tcp:8000 active whenever an emulator starts."""
    while True:
        setup_adb_reverse()
        time.sleep(5)


if __name__ == "__main__":
    setup_adb_reverse()
    threading.Thread(target=setup_adb_reverse_loop, daemon=True).start()
    app.run(host="0.0.0.0", port=8000, debug=False)
