from contextlib import asynccontextmanager
from datetime import datetime, timezone
import io
import os
import sys
from typing import Any, Dict, Literal, Optional

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response

# Kettős import támogatás: működik 'uvicorn Backend.main:app' (gyökérből)
# és 'python Backend/main.py' / 'cd Backend && uvicorn main:app' indítással is!
BACKEND_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(BACKEND_DIR)
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

from Backend import database, schemas
from Backend.rule_engine import (
    EMERGENCY_STOP_COMMAND,
    TelemetryPipeline,
    TelemetrySample,
)
from Backend.telemetry_generator import BackgroundSimulatorService


# Központi Hot/Cold Path Pipeline és Háttér Uplink Szimulátor példányok
pipeline = TelemetryPipeline()
simulator_service = BackgroundSimulatorService(pipeline)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    database.init_db()
    yield
    simulator_service.stop()


app = FastAPI(
    title="Mini Sumo Telemetry & Safety API",
    description=(
        "Ipari szintű telemetriai, Rule Engine (Hot Path vészleállítás / Cold Path SQLite naplózás) "
        "és analitikai REST API a Mini Sumo Robot Webes Dashboard és Android Mobilalkalmazás számára."
    ),
    version="2.0.0",
    lifespan=lifespan,
)

# Engedélyezzük a CORS-t, hogy a webes dashboard (akár file://-ról megnyitva is) és az app hívhassa a szervert
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Content-Disposition"],
)


# =====================================================================
# 0. WEB DASHBOARD KISZOLGÁLÁS & RENDSZER HEALTH CHECK
# =====================================================================


@app.api_route("/", methods=["GET", "HEAD"], include_in_schema=False)
@app.api_route("/dashboard", methods=["GET", "HEAD"], include_in_schema=False)
def serve_web_dashboard():
    """
    Közvetlenül kiszolgálja a Webes Vezérlőpultot (src/web/index.html) a FastAPI szerverről,
    így egyetlen porton (pl. http://127.0.0.1:8000/) érhető el a Web UI és a teljes REST API.
    """
    primary_html = os.path.join(PROJECT_ROOT, "src", "web", "index.html")
    fallback_html = os.path.join(PROJECT_ROOT, "Web", "index.html")
    target = primary_html if os.path.isfile(primary_html) else fallback_html
    if not os.path.isfile(target):
        return JSONResponse(
            {"message": "Mini Sumo Telemetry API fut. Nyisd meg a /docs végpontot az interaktív Swagger dokumentációhoz."}
        )
    return FileResponse(target, media_type="text/html; charset=utf-8")


@app.get("/api/health", tags=["System & Health"])
def health_check():
    """Szerver, SQLite adatbázis, Rule Engine és háttér-szimulátor állapotellenőrzés."""
    summary = database.get_system_analytics_summary()
    robot = pipeline.robot_controller.get_unified_snapshot()
    return {
        "status": "healthy",
        "database_path": database.DB_PATH,
        "emergency_latched": robot["emergency_stop"],
        "simulator": simulator_service.status(),
        "counts": {
            "live_logs": summary["live_telemetry"]["total_logs"],
            "sessions": summary["mobile_sessions"]["total_sessions"],
            "session_points": summary["mobile_sessions"]["total_points"],
            "emergency_stops": summary["live_telemetry"]["emergency_stops_triggered"],
        },
    }


# =====================================================================
# 1. VALÓS IDEJŰ UPLINK TELEMETRIA (HOT PATH / COLD PATH) & VEZÉRLÉS
# =====================================================================


@app.post(
    "/api/telemetry",
    response_model=schemas.TelemetryUplinkResponse,
    status_code=201,
    tags=["Live Telemetry & Hot/Cold Path"],
)
def ingest_telemetry_uplink(payload: schemas.TelemetryUplinkIn):
    """
    Valós idejű Uplink telemetria fogadása az Adatgenerátortól vagy a Robottól.
    - Mentés előtt lefuttatja a Rule Engine-t:
      * HOT PATH (CRITICAL): Azonnali `STOP\\n` vészleállítást küld a gépnek és nullázza a motorokat.
      * COLD PATH (OK / WARNING / CRITICAL után): Strukturáltan naplózza a mérést és az eseményt SQLite-ba.
    """
    sample = TelemetrySample(
        vbat=payload.vbat,
        dist_l=payload.dist_l,
        dist_r=payload.dist_r,
        line_l=payload.line_l,
        line_r=payload.line_r,
        pwm_l=payload.pwm_l,
        pwm_r=payload.pwm_r,
        motor_temp_c=payload.motor_temp_c,
        mode=payload.mode,
        state=payload.state,
        source=payload.source,
        session_id=payload.session_id,
        timestamp=payload.timestamp,
    )
    return pipeline.process_uplink(sample)


@app.get("/api/telemetry", tags=["Live Telemetry & Hot/Cold Path"])
@app.get("/api/telemetry/latest", tags=["Live Telemetry & Hot/Cold Path"])
def get_live_telemetry():
    """
    Visszaadja az aktuális élő robotállapotot, szenzorértékeket és az esetleges aktív
    Hot Path vészleállítási riasztást (egységesített Web + Mobil App sémával).
    """
    snapshot = pipeline.robot_controller.get_unified_snapshot()
    snapshot["simulator_running"] = simulator_service.status()["running"]
    return snapshot


@app.post("/api/command", tags=["Live Telemetry & Hot/Cold Path"])
def post_robot_command(data: schemas.CommandIn):
    """
    Irányítási parancs fogadása Webes vezérlőpultról vagy Mobilalkalmazásból.
    - Támogatja: F, B, L, R, S parancsokat, közvetlen PWM értékeket (-255..255) és MANUAL/AUTO módváltást.
    - Aktív vészleállítási retesz (Emergency Latch) esetén blokkolja a mozgási parancsokat a feloldásig!
    """
    raw_cmd = (data.command or data.cmd or "").strip().upper()
    scale = max(0.1, min(1.0, data.speed_pct / 100.0))

    command_map: Dict[str, Dict[str, Any]] = {
        "F": {"state": "FORWARD", "pwm_l": int(round(255 * scale)), "pwm_r": int(round(255 * scale))},
        "FORWARD": {"state": "FORWARD", "pwm_l": int(round(255 * scale)), "pwm_r": int(round(255 * scale))},
        "B": {"state": "BACKWARD", "pwm_l": -int(round(200 * scale)), "pwm_r": -int(round(200 * scale))},
        "BACKWARD": {"state": "BACKWARD", "pwm_l": -int(round(200 * scale)), "pwm_r": -int(round(200 * scale))},
        "REVERSE": {"state": "BACKWARD", "pwm_l": -int(round(200 * scale)), "pwm_r": -int(round(200 * scale))},
        "L": {"state": "LEFT", "pwm_l": -int(round(120 * scale)), "pwm_r": int(round(210 * scale))},
        "LEFT": {"state": "LEFT", "pwm_l": -int(round(120 * scale)), "pwm_r": int(round(210 * scale))},
        "R": {"state": "RIGHT", "pwm_l": int(round(210 * scale)), "pwm_r": -int(round(120 * scale))},
        "RIGHT": {"state": "RIGHT", "pwm_l": int(round(210 * scale)), "pwm_r": -int(round(120 * scale))},
        "S": {"state": "STOP", "pwm_l": 0, "pwm_r": 0},
        "STOP": {"state": "STOP", "pwm_l": 0, "pwm_r": 0},
    }

    target_state: Optional[str] = None
    target_pwm_l: Optional[int] = data.pwm_l
    target_pwm_r: Optional[int] = data.pwm_r
    uart_packet: Optional[str] = None

    if raw_cmd:
        if raw_cmd not in command_map:
            raise HTTPException(
                status_code=400,
                detail="Érvénytelen parancs. Engedélyezett értékek: F, B, L, R, S (vagy FORWARD, BACKWARD, LEFT, RIGHT, STOP).",
            )
        mapped = command_map[raw_cmd]
        target_state = mapped["state"]
        target_pwm_l = mapped["pwm_l"]
        target_pwm_r = mapped["pwm_r"]
        uart_packet = (
            EMERGENCY_STOP_COMMAND
            if target_state == "STOP"
            else f"M,{target_pwm_l},{target_pwm_r}\n"
        )
    elif target_pwm_l is not None and target_pwm_r is not None:
        target_state = "STOP" if (target_pwm_l == 0 and target_pwm_r == 0) else "CUSTOM_PWM"
        uart_packet = f"M,{target_pwm_l},{target_pwm_r}\n"
    elif data.mode is not None:
        uart_packet = f"MODE,{data.mode}\n"
    else:
        raise HTTPException(status_code=400, detail="Nem érkezett sem parancs (command), sem mód (mode), sem PWM érték.")

    try:
        updated_robot = pipeline.robot_controller.apply_operator_command(
            state=target_state,
            pwm_l=target_pwm_l,
            pwm_r=target_pwm_r,
            mode=data.mode,
            uart_packet=uart_packet,
        )
    except RuntimeError as latch_err:
        return JSONResponse(
            status_code=409,
            content={
                "status": "blocked_by_emergency_latch",
                "message": str(latch_err),
                "robot_state": pipeline.robot_controller.get_unified_snapshot(),
            },
        )

    # Cold Path naplózás az operátori állapotváltásról is
    iso_now, epoch_ms = database.utc_now_iso_and_epoch()
    database.insert_telemetry_log_and_events(
        timestamp=iso_now,
        epoch_ms=epoch_ms,
        session_id=None,
        source="OPERATOR_CMD",
        mode=updated_robot["mode"],
        state=updated_robot["state"],
        vbat=updated_robot["vbat"],
        battery_pct=updated_robot["battery_pct"],
        dist_l=updated_robot["dist_l"],
        dist_r=updated_robot["dist_r"],
        line_l=updated_robot["line_l"],
        line_r=updated_robot["line_r"],
        pwm_l=updated_robot["pwm_l"],
        pwm_r=updated_robot["pwm_r"],
        motor_temp_c=updated_robot["motor_temp_c"],
        validation_status="OK",
        triggered_rules=[],
        emergency_stopped=False,
        events_to_log=[],
    )

    return {
        "status": "ok",
        "command": raw_cmd or data.mode or "PWM",
        "uart_packet": uart_packet,
        "robot_state": updated_robot,
    }


@app.post("/api/emergency-stop", tags=["Live Telemetry & Hot/Cold Path"])
def trigger_manual_emergency_stop(body: Optional[schemas.EmergencyStopIn] = None):
    """Azonnali manuális vészleállítás (STOP\\n) küldése a robotnak és eseménynaplózás."""
    payload = body or schemas.EmergencyStopIn()
    cmd = pipeline.robot_controller.dispatch_emergency_stop(
        reason=payload.reason,
        rule_codes=["MANUAL_EMERGENCY_STOP"],
    )
    event_id = database.record_standalone_event(
        severity="CRITICAL",
        rule_code="MANUAL_EMERGENCY_STOP",
        message=f"[{payload.source}] {payload.reason}",
        action_taken=cmd.strip(),
        snapshot=pipeline.robot_controller.get_unified_snapshot(),
    )
    return {
        "status": "emergency_stopped",
        "downlink_command": cmd,
        "event_id": event_id,
        "robot_state": pipeline.robot_controller.get_unified_snapshot(),
    }


@app.post("/api/emergency-reset", tags=["Live Telemetry & Hot/Cold Path"])
def reset_emergency_latch(body: Optional[schemas.EmergencyResetIn] = None):
    """Feloldja a Hot Path vészleállítási reteszt, hogy a robot újra fogadhasson vezérlést."""
    payload = body or schemas.EmergencyResetIn()
    updated = pipeline.robot_controller.clear_emergency_latch(note=payload.operator_note)
    event_id = database.record_standalone_event(
        severity="INFO",
        rule_code="EMERGENCY_RESET",
        message=payload.operator_note,
        action_taken="LATCH_CLEARED",
        snapshot=updated,
    )
    return {
        "status": "reset_ok",
        "event_id": event_id,
        "robot_state": updated,
    }


@app.get("/api/downlink/outbox", tags=["Live Telemetry & Hot/Cold Path"])
def get_downlink_outbox(limit: int = Query(20, ge=1, le=100)):
    """Lekérdezi a robot felé kiküldött legutóbbi UART/Bluetooth vezérlő és STOP\\n csomagokat."""
    return {"items": pipeline.robot_controller.get_recent_downlink_commands(limit=limit)}


# =====================================================================
# 2. KOVACS1DANI MOBIL APP SESSION VÉGPONTJAI (100% kompatibilis + Rule Engine)
# =====================================================================


@app.post("/api/sessions", status_code=201, tags=["Mobile Sessions (Kovacs1Dani)"])
def upload_session(data: schemas.SessionUpload):
    """
    Az Android app (SessionRecorder.kt) hívja meg a futam (MATCH vagy FREE_ROAM) végén.
    - Megőrzi Kovacs1Dani pontos JSON válasz-szerkezetét ({status, session_id, saved_points}).
    - Bővítés: mentés előtt minden telemetriai ponton lefuttatja a Rule Engine-t,
      megjelöli az anomáliákat, és kritikus értékeknél biztonsági összefoglaló eseményt rögzít!
    """
    records = []
    warning_count = 0
    critical_count = 0
    peak_pwm = 0
    triggered_critical_codes = set()

    for p in data.points:
        sample = TelemetrySample(
            vbat=p.vbat,
            dist_l=p.dist_l,
            dist_r=p.dist_r,
            line_l=p.line_l,
            line_r=p.line_r,
            pwm_l=p.pwm_l,
            pwm_r=p.pwm_r,
            mode="AUTO" if data.session_type == "MATCH" else "MANUAL",
            state="SESSION_POINT",
            source="MOBILE_SESSION",
        )
        eval_res = pipeline.rule_engine.evaluate(sample)
        if eval_res.validation_status == "WARNING":
            warning_count += 1
        elif eval_res.validation_status == "CRITICAL":
            critical_count += 1
            triggered_critical_codes.update(eval_res.triggered_rule_codes)

        peak_pwm = max(peak_pwm, abs(p.pwm_l), abs(p.pwm_r))
        records.append(
            (
                p.rel_time_ms,
                p.vbat,
                p.dist_l,
                p.dist_r,
                p.line_l,
                p.line_r,
                p.pwm_l,
                p.pwm_r,
                eval_res.validation_status,
                ",".join(eval_res.triggered_rule_codes),
            )
        )

    with database.get_connection() as conn:
        cursor = conn.cursor()

        # 1. Munkamenet beszúrása (Kovacs1Dani sémája)
        cursor.execute(
            """
            INSERT INTO sessions (session_type, created_at, duration_ms, start_vbat, end_vbat, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                data.session_type,
                data.created_at,
                data.duration_ms,
                data.start_vbat,
                data.end_vbat,
                data.notes or "",
            ),
        )
        session_id = int(cursor.lastrowid)

        # 2. Validált telemetriai pontok kötegelt mentése (Batch insert)
        db_rows = [(session_id, *rec) for rec in records]
        cursor.executemany(
            """
            INSERT INTO telemetry_points
            (session_id, rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r, validation_status, triggered_rules)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            db_rows,
        )
        conn.commit()

    if critical_count > 0:
        database.record_standalone_event(
            severity="CRITICAL",
            rule_code="SESSION_ANOMALIES_DETECTED",
            message=(
                f"Feltöltött {data.session_type} futamban (#{session_id}) {critical_count} db kritikus "
                f"és {warning_count} db figyelmeztető pont található ({', '.join(sorted(triggered_critical_codes))})."
            ),
            action_taken="AUDIT_LOGGED",
            session_id=session_id,
            snapshot={"peak_pwm": peak_pwm, "critical_points": critical_count, "warning_points": warning_count},
        )

    return {
        "status": "success",
        "session_id": session_id,
        "saved_points": len(data.points),
        "validation_summary": {
            "warning_points": warning_count,
            "critical_points": critical_count,
            "peak_pwm": peak_pwm,
        },
    }


@app.get("/api/sessions", tags=["Mobile Sessions (Kovacs1Dani)"])
def list_sessions(
    session_type: Optional[str] = Query(None, description="Szűrés: MATCH vagy FREE_ROAM"),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    """Listázza a futamokat szűrési lehetőséggel és pontszám/anomália statisztikával."""
    with database.get_connection() as conn:
        cursor = conn.cursor()
        base_sql = """
            SELECT
                s.*,
                COUNT(tp.id) AS point_count,
                COALESCE(MAX(CASE WHEN ABS(tp.pwm_l) > ABS(tp.pwm_r) THEN ABS(tp.pwm_l) ELSE ABS(tp.pwm_r) END), 0) AS peak_pwm,
                SUM(CASE WHEN tp.validation_status = 'CRITICAL' THEN 1 ELSE 0 END) AS critical_points
            FROM sessions s
            LEFT JOIN telemetry_points tp ON tp.session_id = s.id
        """
        if session_type:
            cursor.execute(
                f"{base_sql} WHERE s.session_type = ? GROUP BY s.id ORDER BY s.id DESC LIMIT ? OFFSET ?",
                (session_type.upper(), limit, offset),
            )
        else:
            cursor.execute(
                f"{base_sql} GROUP BY s.id ORDER BY s.id DESC LIMIT ? OFFSET ?",
                (limit, offset),
            )
        rows = [dict(row) for row in cursor.fetchall()]
    return rows


@app.get("/api/sessions/{session_id}/points", tags=["Mobile Sessions (Kovacs1Dani)"])
def get_session_points(session_id: int):
    """Visszaadja a grafikon rajzolásához szükséges pontokat az akku és a szenzorok görbéihez."""
    with database.get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            SELECT rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r,
                   validation_status, triggered_rules
            FROM telemetry_points
            WHERE session_id = ?
            ORDER BY rel_time_ms ASC
            """,
            (session_id,),
        )
        points = [dict(row) for row in cursor.fetchall()]

    if not points:
        raise HTTPException(status_code=404, detail="Nincsenek adatok ehhez a menethez")
    return points


@app.delete("/api/sessions/{session_id}", tags=["Mobile Sessions (Kovacs1Dani)"])
def delete_session(session_id: int):
    """Töröl egy munkamenetet és (ON DELETE CASCADE révén) az összes hozzá tartozó telemetriai pontot."""
    with database.get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("DELETE FROM sessions WHERE id = ?", (session_id,))
        deleted = cursor.rowcount
        conn.commit()
    if deleted == 0:
        raise HTTPException(status_code=404, detail="A megadott session nem található")
    return {"status": "deleted", "session_id": session_id}


# =====================================================================
# 3. TÖRTÉNELMI LEKÉRDEZÉSEK (GET /api/history), ESEMÉNYEK & ANALITIKA
# =====================================================================


@app.get("/api/history", tags=["History & Analytics"])
def get_telemetry_history(
    source: Literal["live", "sessions", "all"] = Query(
        "live", description="Adatforrás: 'live' (élő Uplink logok), 'sessions' (mobil futamok) vagy 'all'"
    ),
    min_pwm: Optional[int] = Query(
        None, ge=0, description="Motor kiugróan magas értékeinek szűrése: |pwm_l| >= min_pwm VAGY |pwm_r| >= min_pwm"
    ),
    max_vbat: Optional[float] = Query(None, description="Alacsony akkufeszültség szűrése (pl. 6.8)"),
    max_dist: Optional[int] = Query(None, ge=0, description="Közeli akadály/ellenfél szűrése (cm, pl. 15)"),
    border_only: bool = Query(False, description="Csak a Dohyo fehér szegély-érintések listázása"),
    status: Optional[Literal["OK", "WARNING", "CRITICAL"]] = Query(
        None, description="Szűrés validációs eredményre (OK, WARNING, CRITICAL)"
    ),
    only_anomalies: bool = Query(False, description="Csak a WARNING és CRITICAL anomáliák lekérése"),
    emergency_only: bool = Query(False, description="Csak a Hot Path vészleállítást kiváltó mérések"),
    session_id: Optional[int] = Query(None, description="Adott futamra (session_id) szűrés"),
    from_ts: Optional[str] = Query(None, description="Időablak kezdete (ISO-8601 vagy epoch ms)"),
    to_ts: Optional[str] = Query(None, description="Időablak vége (ISO-8601 vagy epoch ms)"),
    limit: int = Query(100, ge=1, le=1000, description="Lapozási limit (max 1000)"),
    offset: int = Query(0, ge=0, description="Lapozási eltolás (offset)"),
    order: Literal["desc", "asc"] = Query("desc", description="Időrendi rendezés ('desc' = legfrissebb elöl)"),
):
    """
    Történelmi telemetria-lekérdező végpont Webes Dashboardhoz és Mobilalkalmazáshoz.
    Példák:
    - Kiugró motor PWM értékek: `GET /api/history?min_pwm=240`
    - Csak Hot Path vészleállítások: `GET /api/history?emergency_only=true`
    - Alacsony akkumulátor logok: `GET /api/history?max_vbat=6.8`
    """
    result = database.query_telemetry_history(
        source=source,
        min_pwm=min_pwm,
        max_vbat=max_vbat,
        max_dist=max_dist,
        border_only=border_only,
        status=status,
        only_anomalies=only_anomalies,
        emergency_only=emergency_only,
        session_id=session_id,
        from_ts=from_ts,
        to_ts=to_ts,
        limit=limit,
        offset=offset,
        order=order,
    )
    result["applied_filters"] = {
        "source": source,
        "min_pwm": min_pwm,
        "max_vbat": max_vbat,
        "max_dist": max_dist,
        "border_only": border_only,
        "status": status,
        "only_anomalies": only_anomalies,
        "emergency_only": emergency_only,
        "session_id": session_id,
        "from_ts": from_ts,
        "to_ts": to_ts,
    }
    return result


@app.get("/api/events", tags=["History & Analytics"])
def get_safety_events(
    severity: Optional[Literal["INFO", "WARNING", "CRITICAL"]] = Query(None, description="Súlyosság szűrő"),
    rule_code: Optional[str] = Query(None, description="Szabálykód (pl. MOTOR_PWM_SPIKE, COLLISION_IMMINENT)"),
    session_id: Optional[int] = Query(None, description="Session azonosító szűrő"),
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    """Lekérdezi a Rule Engine által rögzített biztonsági eseményeket és Hot Path vészleállításokat."""
    return database.query_safety_events(
        severity=severity,
        rule_code=rule_code,
        session_id=session_id,
        limit=limit,
        offset=offset,
    )


@app.get("/api/analytics/motor-runtime/{session_id}", tags=["History & Analytics"])
def get_motor_runtime(session_id: int):
    """
    Kovacs1Dani meglévő analitikai végpontja kibővítve csúcs- és átlag-PWM mutatókkal:
    kiszámolja, hány milliszekundumban működtek aktívan a motorok a futam alatt.
    """
    with database.get_connection() as conn:
        cursor = conn.cursor()
        session_exists = cursor.execute("SELECT id FROM sessions WHERE id = ?", (session_id,)).fetchone()
        if not session_exists:
            raise HTTPException(status_code=404, detail="A megadott session nem található")

        res = cursor.execute(
            """
            SELECT
                COUNT(*) AS active_ticks,
                COALESCE(MAX(CASE WHEN ABS(pwm_l) > ABS(pwm_r) THEN ABS(pwm_l) ELSE ABS(pwm_r) END), 0) AS peak_pwm,
                ROUND(COALESCE(AVG((ABS(pwm_l) + ABS(pwm_r)) / 2.0), 0.0), 1) AS avg_motor_pwm
            FROM telemetry_points
            WHERE session_id = ? AND (pwm_l != 0 OR pwm_r != 0)
            """,
            (session_id,),
        ).fetchone()

    # Ha átlagosan 20 ms a mintavétel (50 Hz), a ticks * 20 adja az üzemidőt
    active_ms = int(res["active_ticks"] or 0) * 20
    return {
        "session_id": session_id,
        "active_motor_time_ms": active_ms,
        "peak_pwm": int(res["peak_pwm"] or 0),
        "avg_active_pwm": float(res["avg_motor_pwm"] or 0.0),
    }


@app.get("/api/analytics/summary", tags=["History & Analytics"])
def get_analytics_summary():
    """Globális telemetriai, motorterhelési és biztonsági KPI összefoglaló."""
    return database.get_system_analytics_summary()


@app.get("/api/export/csv", tags=["History & Analytics"])
def export_telemetry_csv(
    session_id: Optional[int] = Query(None, description="Ha meg van adva, az adott mobil futam pontjait exportálja"),
    limit: int = Query(5000, ge=1, le=50000),
):
    """
    Kiadja az SQLite adatbázisban tárolt validált telemetriát pontosan a mobilalkalmazás
    (TelemetryCsvLogger.kt) és a Webes Dashboard RFC-4180 CSV formátumában.
    """
    output = io.StringIO()
    output.write(
        "Timestamp_ms,DateTime,Dist_Left_cm,Dist_Right_cm,Line_Left,Line_Right,Battery_Volts,Battery_Percent\r\n"
    )

    with database.get_connection() as conn:
        cursor = conn.cursor()
        if session_id is not None:
            session_row = cursor.execute(
                "SELECT created_at FROM sessions WHERE id = ?", (session_id,)
            ).fetchone()
            if not session_row:
                raise HTTPException(status_code=404, detail="A megadott session nem található")
            base_epoch = database.parse_iso_to_epoch_ms(session_row["created_at"]) or int(
                datetime.now(timezone.utc).timestamp() * 1000
            )
            rows = cursor.execute(
                """
                SELECT rel_time_ms, vbat, dist_l, dist_r, line_l, line_r
                FROM telemetry_points
                WHERE session_id = ?
                ORDER BY rel_time_ms ASC
                LIMIT ?
                """,
                (session_id, limit),
            ).fetchall()

            for r in rows:
                ts_ms = base_epoch + int(r["rel_time_ms"])
                dt = datetime.fromtimestamp(ts_ms / 1000.0, tz=timezone.utc)
                dt_str = dt.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
                vbat = float(r["vbat"])
                bat_pct = database.calculate_battery_percent(vbat)
                output.write(
                    f'{ts_ms},"{dt_str}",{r["dist_l"]},{r["dist_r"]},{r["line_l"]},{r["line_r"]},{vbat:.2f},{bat_pct}\r\n'
                )
        else:
            rows = cursor.execute(
                """
                SELECT epoch_ms, timestamp, dist_l, dist_r, line_l, line_r, vbat, battery_pct
                FROM (
                    SELECT epoch_ms, timestamp, dist_l, dist_r, line_l, line_r, vbat, battery_pct, id
                    FROM telemetry_logs
                    ORDER BY epoch_ms DESC, id DESC
                    LIMIT ?
                )
                ORDER BY epoch_ms ASC, id ASC
                """,
                (limit,),
            ).fetchall()

            for r in rows:
                ts_ms = int(r["epoch_ms"])
                dt = datetime.fromtimestamp(ts_ms / 1000.0, tz=timezone.utc)
                dt_str = dt.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
                vbat = float(r["vbat"])
                bat_pct = int(r["battery_pct"])
                output.write(
                    f'{ts_ms},"{dt_str}",{r["dist_l"]},{r["dist_r"]},{r["line_l"]},{r["line_r"]},{vbat:.2f},{bat_pct}\r\n'
                )

    file_ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"sumo_telemetry_{file_ts}.csv"
    return Response(
        content=output.getvalue(),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# =====================================================================
# 4. BEÉPÍTETT UPLINK SZIMULÁTOR VEZÉRLŐ VÉGPONTOK (DEMÓ & TESZTELÉS)
# =====================================================================


@app.post("/api/simulator/start", tags=["Uplink Simulator"])
def start_background_simulator(config: Optional[schemas.SimulatorConfigIn] = None):
    """Elindítja a szerveroldali folyamatos Uplink adatgenerátort."""
    cfg = config or schemas.SimulatorConfigIn()
    return simulator_service.start(
        interval_sec=cfg.interval_sec,
        anomaly_probability=cfg.anomaly_probability,
    )


@app.post("/api/simulator/stop", tags=["Uplink Simulator"])
def stop_background_simulator():
    """Leállítja a szerveroldali folyamatos Uplink adatgenerátort."""
    return simulator_service.stop()


@app.get("/api/simulator/status", tags=["Uplink Simulator"])
def get_simulator_status():
    """Lekérdezi a háttér-adatgenerátor állapotát."""
    return simulator_service.status()


@app.post("/api/simulator/inject-anomaly", tags=["Uplink Simulator"])
def inject_test_anomaly(
    scenario: Literal[
        "MOTOR_PWM_SPIKE",
        "COLLISION_IMMINENT",
        "DOHYO_FALL_DANGER",
        "BATTERY_DEEP_DISCHARGE",
        "MOTOR_OVERHEAT_CRITICAL",
    ] = Query("MOTOR_PWM_SPIKE", description="Szimulálandó kritikus veszélyhelyzet (Hot Path teszt)")
):
    """
    Egyetlen célzott kritikus anomália csomagot küld a TelemetryPipeline-ba,
    hogy azonnal demonstrálható legyen a Hot Path (`STOP\\n`) és Cold Path (SQLite) működése.
    """
    return simulator_service.inject_single_packet(scenario=scenario)


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PORT", "8000"))
    host = os.environ.get("HOST", "0.0.0.0")
    print(f"🚀 Mini Sumo FastAPI Backend indul: http://127.0.0.1:{port} (Swagger UI: http://127.0.0.1:{port}/docs)")
    uvicorn.run(app, host=host, port=port)
