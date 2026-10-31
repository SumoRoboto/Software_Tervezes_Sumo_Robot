import json
import os
import sqlite3
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

# Abszolút útvonal alapértelmezésként, hogy gyökérből és Backend/ mappából indítva is ugyanazt használja
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("SUMO_DB_PATH", os.path.join(BASE_DIR, "sumo_telemetry.db"))


def utc_now_iso_and_epoch() -> Tuple[str, int]:
    """Visszaadja az aktuális UTC időbélyeget ISO-8601 formátumban (ezredmásodperccel) és epoch ms-ben."""
    now = datetime.now(timezone.utc)
    iso_str = now.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    epoch_ms = int(now.timestamp() * 1000)
    return iso_str, epoch_ms


def parse_iso_to_epoch_ms(ts_str: str) -> Optional[int]:
    """ISO-8601 vagy 'YYYY-MM-DD HH:MM:SS' időbélyeg konvertálása epoch milliszekundummá."""
    if not ts_str:
        return None
    cleaned = ts_str.strip()
    if cleaned.isdigit():
        return int(cleaned)
    try:
        if cleaned.endswith("Z"):
            cleaned = cleaned[:-1] + "+00:00"
        dt = datetime.fromisoformat(cleaned)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp() * 1000)
    except ValueError:
        return None


def calculate_battery_percent(v: float) -> int:
    """
    Pontosan a mobilalkalmazás (BatteryState.kt) 2S LiPo számítási logikája:
    - FULL_VOLTAGE = 8.4V (100%)
    - NOMINAL_VOLTAGE = 7.4V (50%)
    - CRITICAL_VOLTAGE = 6.8V (figyelmeztetési küszöb)
    - MIN_VOLTAGE = 6.0V (0%)
    """
    full_v = 8.4
    nominal_v = 7.4
    min_v = 6.0
    if v <= 0.1:
        return 0
    if v >= full_v:
        return 100
    if v <= min_v:
        return 0
    if v >= nominal_v:
        ratio = (v - nominal_v) / (full_v - nominal_v)
        return max(0, min(100, int(50 + ratio * 50)))
    ratio = (v - min_v) / (nominal_v - min_v)
    return max(0, min(100, int(ratio * 50)))


def get_connection(db_path: Optional[str] = None) -> sqlite3.Connection:
    """SQLite kapcsolat megnyitása Row factory-val, WAL móddal és idegenkulcs-ellenőrzéssel."""
    target = db_path or DB_PATH
    conn = sqlite3.connect(target, timeout=10.0, check_same_thread=False)
    conn.row_factory = sqlite3.Row  # Oszlopnevek szerinti eléréshez (Kovacs1Dani konvenciója)
    conn.execute("PRAGMA foreign_keys = ON;")
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA synchronous = NORMAL;")
    return conn


def _ensure_column(cursor: sqlite3.Cursor, table: str, column: str, definition: str) -> None:
    """Biztonságos séma-migráció meglévő SQLite táblákhoz adatvesztés nélkül."""
    cursor.execute(f"PRAGMA table_info({table});")
    existing_cols = {row["name"] for row in cursor.fetchall()}
    if column not in existing_cols:
        cursor.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition};")


def init_db() -> None:
    """
    Inicializálja és kibővíti Kovacs1Dani SQLite sémáját:
    1. sessions (meglévő mobil app tábla - változatlan maggal)
    2. telemetry_points (meglévő mobil app futam-pontok + validációs státusz oszlopok)
    3. telemetry_logs (Cold Path: valós idejű Uplink telemetria napló)
    4. safety_events (Hot/Cold Path: vészleállítások és Rule Engine riasztások audit naplója)
    """
    with get_connection() as conn:
        cursor = conn.cursor()

        # 1. Kovacs1Dani meglévő 'sessions' táblája
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_type TEXT NOT NULL,
            created_at TEXT NOT NULL,
            duration_ms INTEGER NOT NULL,
            start_vbat REAL NOT NULL,
            end_vbat REAL NOT NULL,
            notes TEXT DEFAULT ''
        );
        """)

        # 2. Kovacs1Dani meglévő 'telemetry_points' táblája (+ visszafelé kompatibilis validációs oszlopok)
        cursor.execute("""
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
            validation_status TEXT NOT NULL DEFAULT 'OK',
            triggered_rules TEXT NOT NULL DEFAULT '',
            FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
        );
        """)
        _ensure_column(cursor, "telemetry_points", "validation_status", "TEXT NOT NULL DEFAULT 'OK'")
        _ensure_column(cursor, "telemetry_points", "triggered_rules", "TEXT NOT NULL DEFAULT ''")

        # 3. ÚJ: Cold Path élő telemetriai napló ('telemetry_logs')
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS telemetry_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            epoch_ms INTEGER NOT NULL,
            session_id INTEGER NULL,
            source TEXT NOT NULL DEFAULT 'UPLINK',
            mode TEXT NOT NULL DEFAULT 'MANUAL',
            state TEXT NOT NULL DEFAULT 'STOP',
            vbat REAL NOT NULL,
            battery_pct INTEGER NOT NULL,
            dist_l INTEGER NOT NULL,
            dist_r INTEGER NOT NULL,
            line_l INTEGER NOT NULL,
            line_r INTEGER NOT NULL,
            pwm_l INTEGER NOT NULL,
            pwm_r INTEGER NOT NULL,
            motor_temp_c REAL NOT NULL DEFAULT 25.0,
            validation_status TEXT NOT NULL DEFAULT 'OK',
            triggered_rules TEXT NOT NULL DEFAULT '',
            emergency_stopped INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE SET NULL
        );
        """)

        # 4. ÚJ: Biztonsági és Rule Engine eseménynapló ('safety_events')
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS safety_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            epoch_ms INTEGER NOT NULL,
            session_id INTEGER NULL,
            telemetry_log_id INTEGER NULL,
            severity TEXT NOT NULL,
            rule_code TEXT NOT NULL,
            message TEXT NOT NULL,
            action_taken TEXT NOT NULL DEFAULT 'NONE',
            snapshot_json TEXT NOT NULL DEFAULT '{}',
            FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE SET NULL,
            FOREIGN KEY (telemetry_log_id) REFERENCES telemetry_logs (id) ON DELETE SET NULL
        );
        """)

        # 5. Indexek a gyors történelmi és analitikai lekérdezésekhez (GET /api/history, /api/events)
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_points_session_time ON telemetry_points (session_id, rel_time_ms);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_points_pwm ON telemetry_points (pwm_l, pwm_r);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_points_status ON telemetry_points (validation_status);")

        cursor.execute("CREATE INDEX IF NOT EXISTS idx_logs_epoch ON telemetry_logs (epoch_ms DESC);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_logs_status ON telemetry_logs (validation_status);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_logs_pwm ON telemetry_logs (pwm_l, pwm_r);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_logs_vbat ON telemetry_logs (vbat);")

        cursor.execute("CREATE INDEX IF NOT EXISTS idx_events_epoch_sev ON safety_events (epoch_ms DESC, severity);")

        conn.commit()


def insert_telemetry_log_and_events(
    *,
    timestamp: str,
    epoch_ms: int,
    session_id: Optional[int],
    source: str,
    mode: str,
    state: str,
    vbat: float,
    battery_pct: int,
    dist_l: int,
    dist_r: int,
    line_l: int,
    line_r: int,
    pwm_l: int,
    pwm_r: int,
    motor_temp_c: float,
    validation_status: str,
    triggered_rules: List[str],
    emergency_stopped: bool,
    events_to_log: List[Dict[str, Any]],
) -> int:
    """
    Cold Path perzisztálás: egyetlen atomi tranzakcióban elmenti a validált telemetriai
    rekordot és az esetlegesen keletkezett WARNING / CRITICAL biztonsági eseményeket.
    """
    rules_csv = ",".join(triggered_rules)
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO telemetry_logs (
                timestamp, epoch_ms, session_id, source, mode, state,
                vbat, battery_pct, dist_l, dist_r, line_l, line_r,
                pwm_l, pwm_r, motor_temp_c,
                validation_status, triggered_rules, emergency_stopped
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                timestamp,
                epoch_ms,
                session_id,
                source,
                mode,
                state,
                round(vbat, 3),
                battery_pct,
                dist_l,
                dist_r,
                line_l,
                line_r,
                pwm_l,
                pwm_r,
                round(motor_temp_c, 2),
                validation_status,
                rules_csv,
                1 if emergency_stopped else 0,
            ),
        )
        telemetry_id = int(cursor.lastrowid)

        for ev in events_to_log:
            cursor.execute(
                """
                INSERT INTO safety_events (
                    timestamp, epoch_ms, session_id, telemetry_log_id,
                    severity, rule_code, message, action_taken, snapshot_json
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    timestamp,
                    epoch_ms,
                    session_id,
                    telemetry_id,
                    ev["severity"],
                    ev["rule_code"],
                    ev["message"],
                    ev["action_taken"],
                    json.dumps(ev.get("snapshot", {}), ensure_ascii=False),
                ),
            )

        conn.commit()
    return telemetry_id


def record_standalone_event(
    *,
    severity: str,
    rule_code: str,
    message: str,
    action_taken: str = "NONE",
    session_id: Optional[int] = None,
    telemetry_log_id: Optional[int] = None,
    snapshot: Optional[Dict[str, Any]] = None,
) -> int:
    """Önálló rendszer- vagy biztonsági esemény (pl. manuális E-Stop vagy Reset) naplózása."""
    ts, epoch_ms = utc_now_iso_and_epoch()
    with get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO safety_events (
                timestamp, epoch_ms, session_id, telemetry_log_id,
                severity, rule_code, message, action_taken, snapshot_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                ts,
                epoch_ms,
                session_id,
                telemetry_log_id,
                severity,
                rule_code,
                message,
                action_taken,
                json.dumps(snapshot or {}, ensure_ascii=False),
            ),
        )
        event_id = int(cursor.lastrowid)
        conn.commit()
    return event_id


def query_telemetry_history(
    *,
    source: str = "live",
    min_pwm: Optional[int] = None,
    max_vbat: Optional[float] = None,
    max_dist: Optional[int] = None,
    border_only: bool = False,
    status: Optional[str] = None,
    only_anomalies: bool = False,
    emergency_only: bool = False,
    session_id: Optional[int] = None,
    from_ts: Optional[str] = None,
    to_ts: Optional[str] = None,
    limit: int = 100,
    offset: int = 0,
    order: str = "desc",
) -> Dict[str, Any]:
    """
    Komplex történelmi lekérdező motor a GET /api/history végponthoz.
    Támogatja mind az élő Uplink naplót ('live'), mind a feltöltött mobil futamokat ('sessions'),
    mind a kettő egyesített nézetét ('all').
    """
    sort_dir = "ASC" if order.lower() == "asc" else "DESC"
    from_epoch = parse_iso_to_epoch_ms(from_ts) if from_ts else None
    to_epoch = parse_iso_to_epoch_ms(to_ts) if to_ts else None

    if source == "sessions":
        base_query = """
            SELECT
                tp.id AS id,
                'SESSION' AS record_source,
                s.created_at AS timestamp,
                tp.rel_time_ms AS rel_time_ms,
                tp.session_id AS session_id,
                s.session_type AS mode,
                'SESSION_POINT' AS state,
                tp.vbat AS vbat,
                tp.dist_l AS dist_l,
                tp.dist_r AS dist_r,
                tp.line_l AS line_l,
                tp.line_r AS line_r,
                tp.pwm_l AS pwm_l,
                tp.pwm_r AS pwm_r,
                25.0 AS motor_temp_c,
                tp.validation_status AS validation_status,
                tp.triggered_rules AS triggered_rules,
                CASE WHEN tp.validation_status = 'CRITICAL' THEN 1 ELSE 0 END AS emergency_stopped
            FROM telemetry_points tp
            JOIN sessions s ON s.id = tp.session_id
        """
        where_clauses: List[str] = []
        params: List[Any] = []

        if session_id is not None:
            where_clauses.append("tp.session_id = ?")
            params.append(session_id)
        if min_pwm is not None:
            where_clauses.append("(ABS(tp.pwm_l) >= ? OR ABS(tp.pwm_r) >= ?)")
            params.extend([min_pwm, min_pwm])
        if max_vbat is not None:
            where_clauses.append("tp.vbat <= ?")
            params.append(max_vbat)
        if max_dist is not None:
            where_clauses.append("(tp.dist_l <= ? OR tp.dist_r <= ?)")
            params.extend([max_dist, max_dist])
        if border_only:
            where_clauses.append("(tp.line_l = 1 OR tp.line_r = 1)")
        if status:
            where_clauses.append("tp.validation_status = ?")
            params.append(status.upper())
        if only_anomalies:
            where_clauses.append("tp.validation_status IN ('WARNING', 'CRITICAL')")
        if emergency_only:
            where_clauses.append("tp.validation_status = 'CRITICAL'")
        if from_ts:
            where_clauses.append("s.created_at >= ?")
            params.append(from_ts)
        if to_ts:
            where_clauses.append("s.created_at <= ?")
            params.append(to_ts)

        where_sql = (" WHERE " + " AND ".join(where_clauses)) if where_clauses else ""
        count_sql = f"SELECT COUNT(*) AS cnt FROM telemetry_points tp JOIN sessions s ON s.id = tp.session_id {where_sql}"
        data_sql = f"{base_query} {where_sql} ORDER BY tp.id {sort_dir} LIMIT ? OFFSET ?"

    elif source == "all":
        union_cte = """
            WITH unified AS (
                SELECT
                    tl.id AS id,
                    tl.source AS record_source,
                    tl.timestamp AS timestamp,
                    tl.epoch_ms AS sort_ts,
                    tl.session_id AS session_id,
                    tl.mode AS mode,
                    tl.state AS state,
                    tl.vbat AS vbat,
                    tl.dist_l AS dist_l,
                    tl.dist_r AS dist_r,
                    tl.line_l AS line_l,
                    tl.line_r AS line_r,
                    tl.pwm_l AS pwm_l,
                    tl.pwm_r AS pwm_r,
                    tl.motor_temp_c AS motor_temp_c,
                    tl.validation_status AS validation_status,
                    tl.triggered_rules AS triggered_rules,
                    tl.emergency_stopped AS emergency_stopped
                FROM telemetry_logs tl
                UNION ALL
                SELECT
                    tp.id AS id,
                    'SESSION' AS record_source,
                    s.created_at AS timestamp,
                    tp.rel_time_ms AS sort_ts,
                    tp.session_id AS session_id,
                    s.session_type AS mode,
                    'SESSION_POINT' AS state,
                    tp.vbat AS vbat,
                    tp.dist_l AS dist_l,
                    tp.dist_r AS dist_r,
                    tp.line_l AS line_l,
                    tp.line_r AS line_r,
                    tp.pwm_l AS pwm_l,
                    tp.pwm_r AS pwm_r,
                    25.0 AS motor_temp_c,
                    tp.validation_status AS validation_status,
                    tp.triggered_rules AS triggered_rules,
                    CASE WHEN tp.validation_status = 'CRITICAL' THEN 1 ELSE 0 END AS emergency_stopped
                FROM telemetry_points tp
                JOIN sessions s ON s.id = tp.session_id
            )
        """
        where_clauses = []
        params = []
        if session_id is not None:
            where_clauses.append("session_id = ?")
            params.append(session_id)
        if min_pwm is not None:
            where_clauses.append("(ABS(pwm_l) >= ? OR ABS(pwm_r) >= ?)")
            params.extend([min_pwm, min_pwm])
        if max_vbat is not None:
            where_clauses.append("vbat <= ?")
            params.append(max_vbat)
        if max_dist is not None:
            where_clauses.append("(dist_l <= ? OR dist_r <= ?)")
            params.extend([max_dist, max_dist])
        if border_only:
            where_clauses.append("(line_l = 1 OR line_r = 1)")
        if status:
            where_clauses.append("validation_status = ?")
            params.append(status.upper())
        if only_anomalies:
            where_clauses.append("validation_status IN ('WARNING', 'CRITICAL')")
        if emergency_only:
            where_clauses.append("emergency_stopped = 1")
        if from_ts:
            where_clauses.append("timestamp >= ?")
            params.append(from_ts)
        if to_ts:
            where_clauses.append("timestamp <= ?")
            params.append(to_ts)

        where_sql = (" WHERE " + " AND ".join(where_clauses)) if where_clauses else ""
        count_sql = f"{union_cte} SELECT COUNT(*) AS cnt FROM unified {where_sql}"
        data_sql = f"{union_cte} SELECT * FROM unified {where_sql} ORDER BY timestamp {sort_dir}, id {sort_dir} LIMIT ? OFFSET ?"

    else:
        # Alapértelmezett: 'live' (telemetry_logs tábla)
        base_query = """
            SELECT
                id,
                source AS record_source,
                timestamp,
                epoch_ms,
                session_id,
                mode,
                state,
                vbat,
                battery_pct,
                dist_l,
                dist_r,
                line_l,
                line_r,
                pwm_l,
                pwm_r,
                motor_temp_c,
                validation_status,
                triggered_rules,
                emergency_stopped
            FROM telemetry_logs
        """
        where_clauses = []
        params = []

        if session_id is not None:
            where_clauses.append("session_id = ?")
            params.append(session_id)
        if min_pwm is not None:
            where_clauses.append("(ABS(pwm_l) >= ? OR ABS(pwm_r) >= ?)")
            params.extend([min_pwm, min_pwm])
        if max_vbat is not None:
            where_clauses.append("vbat <= ?")
            params.append(max_vbat)
        if max_dist is not None:
            where_clauses.append("(dist_l <= ? OR dist_r <= ?)")
            params.extend([max_dist, max_dist])
        if border_only:
            where_clauses.append("(line_l = 1 OR line_r = 1)")
        if status:
            where_clauses.append("validation_status = ?")
            params.append(status.upper())
        if only_anomalies:
            where_clauses.append("validation_status IN ('WARNING', 'CRITICAL')")
        if emergency_only:
            where_clauses.append("emergency_stopped = 1")
        if from_epoch is not None:
            where_clauses.append("epoch_ms >= ?")
            params.append(from_epoch)
        elif from_ts:
            where_clauses.append("timestamp >= ?")
            params.append(from_ts)
        if to_epoch is not None:
            where_clauses.append("epoch_ms <= ?")
            params.append(to_epoch)
        elif to_ts:
            where_clauses.append("timestamp <= ?")
            params.append(to_ts)

        where_sql = (" WHERE " + " AND ".join(where_clauses)) if where_clauses else ""
        count_sql = f"SELECT COUNT(*) AS cnt FROM telemetry_logs {where_sql}"
        data_sql = f"{base_query} {where_sql} ORDER BY epoch_ms {sort_dir}, id {sort_dir} LIMIT ? OFFSET ?"

    with get_connection() as conn:
        cursor = conn.cursor()
        total = int(cursor.execute(count_sql, params).fetchone()["cnt"])
        rows = cursor.execute(data_sql, [*params, limit, offset]).fetchall()

    items: List[Dict[str, Any]] = []
    for row in rows:
        item = dict(row)
        if "battery_pct" not in item or item["battery_pct"] is None:
            item["battery_pct"] = calculate_battery_percent(float(item["vbat"]))
        rules_raw = item.get("triggered_rules") or ""
        item["triggered_rules_list"] = [r for r in rules_raw.split(",") if r]
        item["emergency_stopped"] = bool(item.get("emergency_stopped", 0))
        items.append(item)

    return {
        "total": total,
        "limit": limit,
        "offset": offset,
        "source": source,
        "items": items,
    }


def query_safety_events(
    *,
    severity: Optional[str] = None,
    rule_code: Optional[str] = None,
    session_id: Optional[int] = None,
    limit: int = 50,
    offset: int = 0,
) -> Dict[str, Any]:
    """Biztonsági események és Hot Path vészleállítások lekérdezése."""
    where_clauses: List[str] = []
    params: List[Any] = []

    if severity:
        where_clauses.append("severity = ?")
        params.append(severity.upper())
    if rule_code:
        where_clauses.append("rule_code = ?")
        params.append(rule_code.upper())
    if session_id is not None:
        where_clauses.append("session_id = ?")
        params.append(session_id)

    where_sql = (" WHERE " + " AND ".join(where_clauses)) if where_clauses else ""

    with get_connection() as conn:
        cursor = conn.cursor()
        total = int(
            cursor.execute(f"SELECT COUNT(*) AS cnt FROM safety_events {where_sql}", params).fetchone()["cnt"]
        )
        rows = cursor.execute(
            f"""
            SELECT id, timestamp, epoch_ms, session_id, telemetry_log_id,
                   severity, rule_code, message, action_taken, snapshot_json
            FROM safety_events
            {where_sql}
            ORDER BY epoch_ms DESC, id DESC
            LIMIT ? OFFSET ?
            """,
            [*params, limit, offset],
        ).fetchall()

    items = []
    for r in rows:
        d = dict(r)
        try:
            d["snapshot"] = json.loads(d.pop("snapshot_json") or "{}")
        except json.JSONDecodeError:
            d["snapshot"] = {}
        items.append(d)

    return {"total": total, "limit": limit, "offset": offset, "items": items}


def get_system_analytics_summary() -> Dict[str, Any]:
    """Globális KPI és diagnosztikai összefoglaló Web Dashboardhoz és Mobil Apphoz."""
    with get_connection() as conn:
        cursor = conn.cursor()

        live_stats = dict(
            cursor.execute(
                """
                SELECT
                    COUNT(*) AS total_live_logs,
                    COALESCE(MAX(CASE WHEN ABS(pwm_l) > ABS(pwm_r) THEN ABS(pwm_l) ELSE ABS(pwm_r) END), 0) AS peak_pwm_live,
                    ROUND(COALESCE(MIN(vbat), 0.0), 2) AS min_vbat_live,
                    ROUND(COALESCE(AVG(vbat), 0.0), 2) AS avg_vbat_live,
                    ROUND(COALESCE(MAX(motor_temp_c), 0.0), 1) AS max_motor_temp_c,
                    SUM(CASE WHEN validation_status = 'WARNING' THEN 1 ELSE 0 END) AS warning_logs,
                    SUM(CASE WHEN validation_status = 'CRITICAL' THEN 1 ELSE 0 END) AS critical_logs,
                    SUM(CASE WHEN emergency_stopped = 1 THEN 1 ELSE 0 END) AS emergency_stops
                FROM telemetry_logs
                """
            ).fetchone()
        )

        session_stats = dict(
            cursor.execute(
                """
                SELECT
                    (SELECT COUNT(*) FROM sessions) AS total_sessions,
                    COUNT(*) AS total_session_points,
                    COALESCE(MAX(CASE WHEN ABS(pwm_l) > ABS(pwm_r) THEN ABS(pwm_l) ELSE ABS(pwm_r) END), 0) AS peak_pwm_sessions,
                    SUM(CASE WHEN validation_status = 'CRITICAL' THEN 1 ELSE 0 END) AS critical_session_points
                FROM telemetry_points
                """
            ).fetchone()
        )

        rule_breakdown = [
            dict(r)
            for r in cursor.execute(
                """
                SELECT rule_code, severity, COUNT(*) AS occurrences
                FROM safety_events
                GROUP BY rule_code, severity
                ORDER BY occurrences DESC
                LIMIT 10
                """
            ).fetchall()
        ]

    return {
        "live_telemetry": {
            "total_logs": live_stats["total_live_logs"] or 0,
            "peak_pwm": live_stats["peak_pwm_live"] or 0,
            "min_vbat": live_stats["min_vbat_live"] or 0.0,
            "avg_vbat": live_stats["avg_vbat_live"] or 0.0,
            "max_motor_temp_c": live_stats["max_motor_temp_c"] or 0.0,
            "warning_count": live_stats["warning_logs"] or 0,
            "critical_count": live_stats["critical_logs"] or 0,
            "emergency_stops_triggered": live_stats["emergency_stops"] or 0,
        },
        "mobile_sessions": {
            "total_sessions": session_stats["total_sessions"] or 0,
            "total_points": session_stats["total_session_points"] or 0,
            "peak_pwm": session_stats["peak_pwm_sessions"] or 0,
            "critical_points": session_stats["critical_session_points"] or 0,
        },
        "top_safety_events": rule_breakdown,
    }
