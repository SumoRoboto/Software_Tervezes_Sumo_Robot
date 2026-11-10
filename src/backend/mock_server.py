import io
import json
import os
import random
import sqlite3
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

# Global flag tracking whether the telemetry simulator is active
simulator_running = False


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

        # 1. System configuration table for rule thresholds
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


def check_rules_and_log(measurement):
    """
    Strict Rule Engine: Each measurement is routed to either the Hot Path (critical fault)
    or the Cold Path (normal telemetry), never both at the same time.
    """
    global robot_state
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    raw_json = json.dumps(measurement)

    with sqlite3.connect(DB_PATH) as conn:
        cursor = conn.cursor()

        # 1. Read threshold values from the system_config table
        cursor.execute("SELECT config_key, config_value FROM system_config")
        config = {row[0]: row[1] for row in cursor.fetchall()}

        # 2. Evaluate Hot Path (critical safety rules)
        is_critical = False
        rule_broken = ""
        message = ""

        if measurement["bat"] <= config.get("BATTERY_CRITICAL_MIN", 6.4):
            is_critical = True
            rule_broken = "BATTERY_CRITICAL"
            message = "Kritikus akkufeszültség!"

        elif (
            measurement["s1"] <= config.get("OBSTACLE_CRITICAL_CM", 5.0)
            or measurement["s2"] <= config.get("OBSTACLE_CRITICAL_CM", 5.0)
        ):
            is_critical = True
            rule_broken = "OBSTACLE_COLLISION"
            message = "Ütközésveszély! Túl közeli akadály."

        # 3. Mutually exclusive database insert (Hot Path vs. Cold Path)
        if is_critical:
            # Hot Path: Save only to safety_events
            robot_state["last_alert"] = rule_broken
            robot_state["last_message"] = message
            robot_state["path"] = "HOT"
            cursor.execute(
                """INSERT INTO safety_events (timestamp, severity, rule_name, message, raw_measurement)
                   VALUES (?, ?, ?, ?, ?)""",
                (now, "CRITICAL", rule_broken, message, raw_json),
            )
        else:
            # Cold Path: Save only to telemetry_logs
            robot_state["last_alert"] = None
            robot_state["last_message"] = None
            robot_state["path"] = "COLD"
            cursor.execute(
                """INSERT INTO telemetry_logs (timestamp, raw_measurement, status)
                   VALUES (?, ?, ?)""",
                (now, raw_json, "OK"),
            )
        conn.commit()


@app.route("/api/telemetry", methods=["GET"])
def get_telemetry():
    """
    Live telemetry endpoint:
    When simulator_running is True, generates exactly one new sample per poll,
    evaluates it through the Rule Engine, logs it to SQLite, and returns it to the client.
    This guarantees 1-to-1 synchronization between the web console and the database.
    """
    global robot_state
    if simulator_running:
        fake_data = {
            "bat": round(random.uniform(6.0, 8.4), 2),
            "s1": random.randint(2, 80),
            "s2": random.randint(2, 80),
            "pwmA": random.randint(0, 255),
            "state": "FORWARD",
        }
        robot_state.update(fake_data)
        check_rules_and_log(fake_data)

    response_data = robot_state.copy()
    response_data["is_simulating"] = simulator_running
    return jsonify(response_data), 200


@app.route("/api/simulator/start", methods=["POST"])
def start_simulator():
    """Enable telemetry generation on subsequent /api/telemetry polls."""
    global simulator_running
    if not simulator_running:
        simulator_running = True
        return jsonify({"status": "ok", "message": "Szimulátor elindítva."}), 200
    return jsonify({"status": "ok", "message": "Már fut."}), 200


@app.route("/api/simulator/stop", methods=["POST"])
def stop_simulator():
    """Disable telemetry generation."""
    global simulator_running
    simulator_running = False
    return jsonify({"status": "ok", "message": "Szimulátor leállítva."}), 200


@app.route("/api/database/clear", methods=["DELETE"])
def clear_database():
    """Clear all generated telemetry logs, safety events, and sessions, resetting auto-increment IDs."""
    global simulator_running
    simulator_running = False
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
            # Query critical safety events (Hot Path)
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
            # Query normal telemetry logs (Cold Path)
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
    Persists session summary in 'sessions' and points in 'telemetry_points'.
    Evaluates points through the Rule Engine into Cold/Hot Path tables.
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
            vbat = float(p.get("vbat", 0.0))
            dist_l = int(p.get("dist_l", 0))
            dist_r = int(p.get("dist_r", 0))
            line_l = int(p.get("line_l", 0))
            line_r = int(p.get("line_r", 0))
            pwm_l = int(p.get("pwm_l", 0))
            pwm_r = int(p.get("pwm_r", 0))
            point_records.append((session_id, rel_time, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r))

        if point_records:
            cursor.executemany(
                """
                INSERT INTO telemetry_points (session_id, rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                point_records,
            )

        # Mirror points into cold/hot path tables via Rule Engine criteria
        for p in points:
            meas = {
                "bat": float(p.get("vbat", 8.4)),
                "s1": int(p.get("dist_l", 50)),
                "s2": int(p.get("dist_r", 50)),
                "pwmA": int(p.get("pwm_l", 0)),
                "pwmB": int(p.get("pwm_r", 0)),
                "state": session_type,
            }
            if meas["bat"] <= 6.4:
                cursor.execute(
                    """INSERT INTO safety_events (timestamp, severity, rule_name, message, raw_measurement)
                       VALUES (?, ?, ?, ?, ?)""",
                    (created_at, "CRITICAL", "BATTERY_CRITICAL", f"Session #{session_id}: Kritikus akku ({meas['bat']}V)", json.dumps(meas)),
                )
            elif meas["s1"] <= 5 or meas["s2"] <= 5:
                cursor.execute(
                    """INSERT INTO safety_events (timestamp, severity, rule_name, message, raw_measurement)
                       VALUES (?, ?, ?, ?, ?)""",
                    (created_at, "CRITICAL", "OBSTACLE_COLLISION", f"Session #{session_id}: Ütközésveszély! ({min(meas['s1'], meas['s2'])}cm)", json.dumps(meas)),
                )
            else:
                cursor.execute(
                    """INSERT INTO telemetry_logs (timestamp, raw_measurement, status)
                       VALUES (?, ?, ?)""",
                    (created_at, json.dumps(meas), "OK"),
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

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=True)
