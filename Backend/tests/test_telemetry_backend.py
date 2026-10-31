import os
import tempfile
import pytest
from fastapi.testclient import TestClient

from Backend import database
from Backend.main import app, pipeline
from Backend.telemetry_generator import SumoRobotTelemetryGenerator


@pytest.fixture()
def client():
    """Izolált, ideiglenes SQLite adatbázissal és tiszta robot-állapotgéppel futó tesztkliens."""
    with tempfile.TemporaryDirectory() as tmp_dir:
        test_db = os.path.join(tmp_dir, "test_sumo_telemetry.db")
        old_db = database.DB_PATH
        database.DB_PATH = test_db
        database.init_db()
        pipeline.robot_controller.clear_emergency_latch("Test setup reset")
        pipeline.robot_controller._state["emergency_count"] = 0

        with TestClient(app) as c:
            yield c

        database.DB_PATH = old_db


def test_web_dashboard_served_from_fastapi_root(client: TestClient):
    resp = client.get("/")
    assert resp.status_code == 200
    assert "text/html" in resp.headers["content-type"]
    assert "Mini Sumo" in resp.text


def test_cold_path_normal_and_uart_warning_telemetry(client: TestClient):
    # 1. Normál Uplink csomag -> Cold Path OK
    resp = client.post(
        "/api/telemetry",
        json={
            "vbat": 8.05,
            "dist_l": 42,
            "dist_r": 48,
            "line_l": 0,
            "line_r": 0,
            "pwm_l": 180,
            "pwm_r": 180,
            "motor_temp_c": 34.0,
            "mode": "AUTO",
            "state": "FORWARD",
        },
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["status"] == "accepted"
    assert body["path_executed"] == "COLD_PATH_ONLY"
    assert body["hot_path"]["validation_status"] == "OK"
    assert body["hot_path"]["emergency_stop"] is False
    assert body["robot_state"]["pwm_l"] == 180

    # 2. Nyers hardveres UART csomag (ProtocolParser.kt formátum) alacsony akkuval -> WARNING
    resp_uart = client.post(
        "/api/telemetry",
        json={
            "raw_packet": "TEL,25,30,0,1,6.65\n",
            "pwm_l": -120,
            "pwm_r": -120,
            "state": "BACKWARD",
        },
    )
    assert resp_uart.status_code == 201
    uart_body = resp_uart.json()
    assert uart_body["status"] == "accepted"
    assert uart_body["hot_path"]["validation_status"] == "WARNING"
    assert uart_body["hot_path"]["emergency_stop"] is False
    codes = {v["rule_code"] for v in uart_body["hot_path"]["violations"]}
    assert "BATTERY_LOW_WARNING" in codes
    assert "DOHYO_BORDER_DETECTED" in codes


@pytest.mark.parametrize(
    "payload,expected_rule",
    [
        (
            {"vbat": 7.9, "dist_l": 40, "dist_r": 40, "line_l": 0, "line_r": 0, "pwm_l": 295, "pwm_r": 270},
            "MOTOR_PWM_SPIKE",
        ),
        (
            {"vbat": 7.9, "dist_l": 3, "dist_r": 35, "line_l": 0, "line_r": 0, "pwm_l": 220, "pwm_r": 220},
            "COLLISION_IMMINENT",
        ),
        (
            {"vbat": 7.9, "dist_l": 35, "dist_r": 35, "line_l": 1, "line_r": 0, "pwm_l": 210, "pwm_r": 210},
            "DOHYO_FALL_DANGER",
        ),
        (
            {"vbat": 6.20, "dist_l": 35, "dist_r": 35, "line_l": 0, "line_r": 0, "pwm_l": 150, "pwm_r": 150},
            "BATTERY_DEEP_DISCHARGE",
        ),
    ],
)
def test_hot_path_critical_rules_trigger_immediate_stop_and_safety_latch(
    client: TestClient, payload: dict, expected_rule: str
):
    resp = client.post("/api/telemetry", json=payload)
    assert resp.status_code == 201
    body = resp.json()

    # Hot Path ellenőrzés: azonnali STOP\n vészleállítás és lenullázott motorok!
    assert body["status"] == "emergency_stopped"
    assert body["path_executed"] == "HOT_AND_COLD_PATH"
    assert body["hot_path"]["validation_status"] == "CRITICAL"
    assert body["hot_path"]["emergency_stop"] is True
    assert body["hot_path"]["downlink_command"] == "STOP\n"
    assert any(v["rule_code"] == expected_rule for v in body["hot_path"]["violations"])

    assert body["robot_state"]["state"] == "EMERGENCY_STOP"
    assert body["robot_state"]["pwm_l"] == 0
    assert body["robot_state"]["pwm_r"] == 0
    assert body["robot_state"]["emergency_stop"] is True

    # Safety Interlock: aktív vészállapot alatt az Előre ('F') parancs blokkolva van (HTTP 409)
    blocked = client.post("/api/command", json={"command": "F"})
    assert blocked.status_code == 409

    # Vészállapot feloldása után újra irányítható a robot
    reset_resp = client.post("/api/emergency-reset", json={"operator_note": "Teszt feloldás"})
    assert reset_resp.status_code == 200
    assert reset_resp.json()["robot_state"]["emergency_stop"] is False

    ok_cmd = client.post("/api/command", json={"command": "F", "speed_pct": 80})
    assert ok_cmd.status_code == 200
    assert ok_cmd.json()["robot_state"]["state"] == "FORWARD"


def test_kovacs1dani_android_session_upload_compatibility_and_history_queries(client: TestClient):
    # Pontosan a SessionModelsTest.kt által elvárt Android JSON struktúra (+ 1 motor spike pont)
    session_payload = {
        "session_type": "MATCH",
        "created_at": "2026-10-31T12:00:00Z",
        "duration_ms": 4500,
        "start_vbat": 8.1,
        "end_vbat": 7.8,
        "notes": "Test Match 1 vs Red Dragon",
        "points": [
            {
                "rel_time_ms": 0,
                "vbat": 8.10,
                "dist_l": 45,
                "dist_r": 50,
                "line_l": 0,
                "line_r": 0,
                "pwm_l": 0,
                "pwm_r": 0,
            },
            {
                "rel_time_ms": 120,
                "vbat": 7.92,
                "dist_l": 15,
                "dist_r": 40,
                "line_l": 0,
                "line_r": 1,
                "pwm_l": 200,
                "pwm_r": -150,
            },
            {
                "rel_time_ms": 240,
                "vbat": 7.80,
                "dist_l": 18,
                "dist_r": 20,
                "line_l": 0,
                "line_r": 0,
                "pwm_l": 285,
                "pwm_r": 285,
            },
        ],
    }

    upload_resp = client.post("/api/sessions", json=session_payload)
    assert upload_resp.status_code == 201
    up_body = upload_resp.json()
    assert up_body["status"] == "success"
    assert up_body["saved_points"] == 3
    session_id = up_body["session_id"]

    # Dani végpontjai: listázás, pontok, motor üzemidő
    sessions = client.get("/api/sessions?session_type=MATCH").json()
    assert len(sessions) == 1
    assert sessions[0]["id"] == session_id

    points = client.get(f"/api/sessions/{session_id}/points").json()
    assert len(points) == 3
    assert points[2]["validation_status"] == "CRITICAL"

    runtime = client.get(f"/api/analytics/motor-runtime/{session_id}").json()
    assert runtime["active_motor_time_ms"] == 40  # 2 aktív tick * 20 ms
    assert runtime["peak_pwm"] == 285

    # Élő telemetria beszúrása és GET /api/history motorcsúcs szűrés tesztelése
    client.post(
        "/api/telemetry",
        json={"vbat": 7.95, "dist_l": 50, "dist_r": 50, "line_l": 0, "line_r": 0, "pwm_l": 90, "pwm_r": 90},
    )
    client.post(
        "/api/telemetry",
        json={"vbat": 7.70, "dist_l": 30, "dist_r": 30, "line_l": 0, "line_r": 0, "pwm_l": 265, "pwm_r": 260},
    )

    # Kiugróan magas motorértékek lekérdezése mindkét forrásból (live + sessions)
    high_pwm_hist = client.get("/api/history?source=all&min_pwm=250").json()
    assert high_pwm_hist["total"] == 2
    for item in high_pwm_hist["items"]:
        assert max(abs(item["pwm_l"]), abs(item["pwm_r"])) >= 250

    # Eseménynapló és CSV export ellenőrzése
    events = client.get("/api/events?severity=CRITICAL").json()
    assert events["total"] >= 2

    csv_resp = client.get("/api/export/csv")
    assert csv_resp.status_code == 200
    assert "Timestamp_ms,DateTime,Dist_Left_cm,Dist_Right_cm" in csv_resp.text


def test_python_data_generator_produces_valid_and_anomalous_packets(client: TestClient):
    gen = SumoRobotTelemetryGenerator(anomaly_probability=0.35, seed=42)
    emergency_hits = 0

    for _ in range(20):
        pkt = gen.next_payload(source="UNIT_TEST_GEN")
        resp = client.post("/api/telemetry", json=pkt)
        assert resp.status_code == 201
        res = resp.json()
        if res["hot_path"]["emergency_stop"]:
            emergency_hits += 1
            gen.notify_emergency_stop()
            client.post("/api/emergency-reset")
            gen.resume_after_reset()

    assert emergency_hits >= 1
    summary = client.get("/api/analytics/summary").json()
    assert summary["live_telemetry"]["total_logs"] == 20
