import sqlite3

DB_PATH = "sumo_telemetry.db"

def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row  # Oszlopnevek szerinti eléréshez
    return conn

def init_db():
    with get_connection() as conn:
        cursor = conn.cursor()
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
            FOREIGN KEY (session_id) REFERENCES sessions (id) ON DELETE CASCADE
        );
        """)
        conn.commit()
