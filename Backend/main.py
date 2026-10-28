from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from typing import Optional, List
import schemas
import database

app = FastAPI(title="Mini Sumo Telemetry API")

# Engedélyezzük a CORS-t, hogy a webes dashboard hívhassa a szervert
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.on_event("startup")
def startup():
    database.init_db()

@app.post("/api/sessions", status_code=201)
def upload_session(data: schemas.SessionUpload):
    """
    Az Android app hívja meg a futam (Match vagy Free Roam) befejezésekor.
    Egyetlen tranzakcióban rögzíti az összefoglalót és a többezer telemetriai pontot.
    """
    with database.get_connection() as conn:
        cursor = conn.cursor()
        
        # 1. Munkamenet beszúrása
        cursor.execute("""
            INSERT INTO sessions (session_type, created_at, duration_ms, start_vbat, end_vbat, notes)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (data.session_type, data.created_at, data.duration_ms, data.start_vbat, data.end_vbat, data.notes))
        
        session_id = cursor.lastrowid
        
        # 2. Telemetriai pontok kötegelt mentése (Batch insert)
        records = [
            (
                session_id, p.rel_time_ms, p.vbat, 
                p.dist_l, p.dist_r, p.line_l, p.line_r, 
                p.pwm_l, p.pwm_r
            )
            for p in data.points
        ]
        
        cursor.executemany("""
            INSERT INTO telemetry_points 
            (session_id, rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, records)
        
        conn.commit()

    return {"status": "success", "session_id": session_id, "saved_points": len(data.points)}

@app.get("/api/sessions")
def list_sessions(session_type: Optional[str] = Query(None, description="Szűrés: MATCH vagy FREE_ROAM")):
    """Listázza a futamokat szűrési lehetőséggel a webes felület vagy az app számára."""
    with database.get_connection() as conn:
        cursor = conn.cursor()
        if session_type:
            cursor.execute("SELECT * FROM sessions WHERE session_type = ? ORDER BY id DESC", (session_type.upper(),))
        else:
            cursor.execute("SELECT * FROM sessions ORDER BY id DESC")
        rows = [dict(row) for row in cursor.fetchall()]
    return rows

@app.get("/api/sessions/{session_id}/points")
def get_session_points(session_id: int):
    """Visszaadja a grafikon rajzolásához szükséges pontokat az akku és a szenzorok görbéihez."""
    with database.get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT rel_time_ms, vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r 
            FROM telemetry_points 
            WHERE session_id = ? 
            ORDER BY rel_time_ms ASC
        """, (session_id,))
        points = [dict(row) for row in cursor.fetchall()]
        
    if not points:
        raise HTTPException(status_code=404, detail="Nincsenek adatok ehhez a menethez")
    return points

@app.get("/api/analytics/motor-runtime/{session_id}")
def get_motor_runtime(session_id: int):
    """Kiszámolja, hány milliszekundumban működtek aktívan a motorok a futam alatt."""
    with database.get_connection() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT COUNT(*) AS active_ticks 
            FROM telemetry_points 
            WHERE session_id = ? AND (pwm_l != 0 OR pwm_r != 0)
        """, (session_id,))
        res = cursor.fetchone()
        
    # Ha átlagosan 20 ms a mintavétel (50 Hz), a ticks * 20 adja az üzemidőt
    active_ms = res["active_ticks"] * 20
    return {"session_id": session_id, "active_motor_time_ms": active_ms}
