"""
Mini Sumo Robot – Folyamatos Uplink Telemetria Adatgenerátor és Szimulátor.

Használható:
1. Önálló CLI Python szkriptként (HTTP POST /api/telemetry felé):
   python Backend/telemetry_generator.py --url http://127.0.0.1:8000 --interval 0.5
2. A FastAPI szerverbe épített háttér-szimulátorként (BackgroundSimulatorService),
   amely közvetlenül a TelemetryPipeline-ba táplálja a generált Uplink csomagokat.
"""

import argparse
import random
import sys
import threading
import time
from typing import Any, Callable, Dict, Optional

try:
    import httpx
except ImportError:  # pragma: no cover
    httpx = None  # type: ignore

try:
    from Backend.rule_engine import TelemetryPipeline, TelemetrySample
    from Backend import database
except ImportError:
    from rule_engine import TelemetryPipeline, TelemetrySample  # type: ignore
    import database  # type: ignore


class SumoRobotTelemetryGenerator:
    """
    Valósághű Mini Sumo Robot fizikai és szenzor szimulátor (Gravitas firmware logikával):
    - 2S LiPo akkumulátor fokozatos merülése (8.40V -> 6.50V, majd akkucsere)
    - Pásztázás (SEARCH), célkövetés (TRACKING), támadás (ATTACK) és Dohyo szélétől tolatás (REVERSE)
    - Konfigurálható eséllyel kritikus anomáliák injektálása a Hot Path / Cold Path teszteléséhez
    """

    ANOMALY_SCENARIOS = (
        "MOTOR_PWM_SPIKE",
        "COLLISION_IMMINENT",
        "DOHYO_FALL_DANGER",
        "BATTERY_DEEP_DISCHARGE",
        "MOTOR_OVERHEAT_CRITICAL",
    )

    def __init__(self, anomaly_probability: float = 0.18, seed: Optional[int] = None) -> None:
        self.rng = random.Random(seed)
        self.anomaly_probability = max(0.0, min(1.0, anomaly_probability))
        self.vbat: float = 8.38
        self.dist_l: int = 48
        self.dist_r: int = 52
        self.line_l: int = 0
        self.line_r: int = 0
        self.pwm_l: int = 0
        self.pwm_r: int = 0
        self.motor_temp_c: float = 28.0
        self.mode: str = "AUTO"
        self.state: str = "SEARCH"
        self.halted_by_estop: bool = False
        self.tick_index: int = 0

    def notify_emergency_stop(self) -> None:
        """A backend Hot Path 'STOP\\n' jelzésére a robot azonnal leállítja a motorjait."""
        self.halted_by_estop = True
        self.pwm_l = 0
        self.pwm_r = 0
        self.state = "STOP"
        self.motor_temp_c = max(27.0, self.motor_temp_c - 3.5)

    def resume_after_reset(self) -> None:
        """Vészállapot nyugtázása után biztonságos alaphelyzetből folytatja a szimulációt."""
        self.halted_by_estop = False
        self.state = "SEARCH"
        self.dist_l = self.rng.randint(32, 65)
        self.dist_r = self.rng.randint(32, 65)
        self.line_l = 0
        self.line_r = 0
        if self.vbat < 6.7:
            self.vbat = 8.35  # Szimulált 2S LiPo akkucsere mélykisülés után
        self.motor_temp_c = min(self.motor_temp_c, 36.0)

    def next_payload(
        self,
        *,
        source: str = "PYTHON_GENERATOR",
        force_anomaly: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Előállítja a következő Uplink telemetriai adatcsomagot."""
        self.tick_index += 1

        if self.halted_by_estop and not force_anomaly:
            return {
                "vbat": round(self.vbat, 2),
                "dist_l": self.dist_l,
                "dist_r": self.dist_r,
                "line_l": 0,
                "line_r": 0,
                "pwm_l": 0,
                "pwm_r": 0,
                "motor_temp_c": round(max(26.0, self.motor_temp_c - 1.2), 1),
                "mode": self.mode,
                "state": "STOP",
                "source": source,
            }

        # 1. Normál 2S LiPo merülés és hőmérséklet dinamika
        active_load = (abs(self.pwm_l) + abs(self.pwm_r)) / 510.0
        self.vbat = round(self.vbat - (0.004 + 0.014 * active_load), 2)
        if self.vbat < 6.85:
            # Normál ciklusban 6.85V-nál "akkut cserélünk", kivéve ha anomália teszt jön
            self.vbat = 8.40

        self.motor_temp_c = round(
            max(26.0, min(56.0, self.motor_temp_c + (active_load * 1.4 - 0.55))),
            1,
        )

        # 2. Szumó állapotgép (Gravitas main.cpp alapján: KERESES -> FORDUL -> TAMADAS -> TOLATAS)
        roll = self.rng.random()
        self.line_l = 0
        self.line_r = 0

        if roll < 0.42:
            self.state = "SEARCH"
            self.dist_l = self.rng.randint(35, 85)
            self.dist_r = self.rng.randint(35, 85)
            self.pwm_l = 110
            self.pwm_r = 65
        elif roll < 0.72:
            self.state = "FORWARD"
            self.dist_l = self.rng.randint(16, 38)
            self.dist_r = self.rng.randint(16, 38)
            self.pwm_l = self.rng.randint(165, 225)
            self.pwm_r = self.pwm_l
        elif roll < 0.88:
            self.state = "LEFT" if self.rng.random() < 0.5 else "RIGHT"
            self.dist_l = self.rng.randint(20, 60)
            self.dist_r = self.rng.randint(20, 60)
            if self.state == "LEFT":
                self.pwm_l, self.pwm_r = -120, 140
            else:
                self.pwm_l, self.pwm_r = 140, -120
        else:
            # Szabályos tolatás a Dohyo szélétől (PWM <= 0 -> csak WARNING, nem leesésveszély)
            self.state = "BACKWARD"
            self.line_l = 1
            self.line_r = 0
            self.dist_l = self.rng.randint(25, 70)
            self.dist_r = self.rng.randint(25, 70)
            self.pwm_l = -170
            self.pwm_r = -170

        # 3. Opcionális vagy véletlenszerű KRITIKUS anomália injektálás (Hot Path teszt)
        chosen_anomaly = force_anomaly
        if chosen_anomaly is None and self.rng.random() < self.anomaly_probability:
            chosen_anomaly = self.rng.choice(self.ANOMALY_SCENARIOS)

        if chosen_anomaly:
            scenario = chosen_anomaly.upper()
            if scenario == "MOTOR_PWM_SPIKE":
                self.state = "ATTACK"
                self.pwm_l = self.rng.randint(275, 330)
                self.pwm_r = self.rng.randint(260, 315)
            elif scenario == "COLLISION_IMMINENT":
                self.state = "ATTACK"
                self.dist_l = self.rng.randint(2, 5)
                self.dist_r = self.rng.randint(3, 6)
                self.pwm_l = 235
                self.pwm_r = 235
            elif scenario == "DOHYO_FALL_DANGER":
                self.state = "FORWARD"
                self.line_l = 1
                self.line_r = 1
                self.pwm_l = 215
                self.pwm_r = 215
            elif scenario == "BATTERY_DEEP_DISCHARGE":
                self.vbat = round(self.rng.uniform(5.95, 6.32), 2)
                self.pwm_l = 150
                self.pwm_r = 150
            elif scenario == "MOTOR_OVERHEAT_CRITICAL":
                self.motor_temp_c = round(self.rng.uniform(76.5, 84.0), 1)
                self.pwm_l = 220
                self.pwm_r = 220

        return {
            "vbat": round(self.vbat, 2),
            "dist_l": self.dist_l,
            "dist_r": self.dist_r,
            "line_l": self.line_l,
            "line_r": self.line_r,
            "pwm_l": self.pwm_l,
            "pwm_r": self.pwm_r,
            "motor_temp_c": round(self.motor_temp_c, 1),
            "mode": self.mode,
            "state": self.state,
            "source": source,
        }


class BackgroundSimulatorService:
    """
    A FastAPI szerver mellé csatolható, szálbiztos háttér Uplink generátor,
    hogy a Webes Dashboardról vagy API-ból is egyetlen kattintással indítható legyen.
    """

    def __init__(self, pipeline: TelemetryPipeline) -> None:
        self._pipeline = pipeline
        self._generator = SumoRobotTelemetryGenerator()
        self._lock = threading.Lock()
        self._running = False
        self._interval_sec = 0.7
        self._thread: Optional[threading.Thread] = None
        self._packets_sent = 0
        self._estop_Cooldown_ticks = 0

    def start(self, interval_sec: float = 0.7, anomaly_probability: float = 0.18) -> Dict[str, Any]:
        with self._lock:
            self._interval_sec = max(0.1, min(5.0, interval_sec))
            self._generator.anomaly_probability = max(0.0, min(1.0, anomaly_probability))
            if self._running and self._thread and self._thread.is_alive():
                return self.status()
            self._running = True
            self._thread = threading.Thread(target=self._run_loop, name="SumoUplinkSim", daemon=True)
            self._thread.start()
            return self.status()

    def stop(self) -> Dict[str, Any]:
        with self._lock:
            self._running = False
        return self.status()

    def status(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "running": self._running,
                "interval_sec": self._interval_sec,
                "anomaly_probability": self._generator.anomaly_probability,
                "packets_generated": self._packets_sent,
            }

    def inject_single_packet(self, scenario: Optional[str] = None) -> Dict[str, Any]:
        """Egyetlen szimulált (normál vagy célzott kritikus anomália) csomag beküldése a Pipeline-ba."""
        payload = self._generator.next_payload(source="SIMULATOR_INJECT", force_anomaly=scenario)
        sample = TelemetrySample(**payload)
        result = self._pipeline.process_uplink(sample)
        with self._lock:
            self._packets_sent += 1
        if result["hot_path"]["emergency_stop"]:
            self._generator.notify_emergency_stop()
        return result

    def _run_loop(self) -> None:
        while True:
            with self._lock:
                if not self._running:
                    break
                interval = self._interval_sec

            # Ha vészleállítás történt, a szimulátor 3 ciklusig állva marad (tiszteletben tartja a STOP\n parancsot),
            # majd automatikusan nyugtázza a vészállapotot a folyamatos demóhoz.
            if self._pipeline.robot_controller.get_unified_snapshot()["emergency_stop"]:
                self._estop_Cooldown_ticks += 1
                if self._estop_Cooldown_ticks >= 4:
                    self._pipeline.robot_controller.clear_emergency_latch("Auto-simulator safety cooldown lejárt")
                    database.record_standalone_event(
                        severity="INFO",
                        rule_code="SIMULATOR_AUTO_RESET",
                        message="Szimulátor vészleállítási szünet lejárt, biztonsági retesz feloldva.",
                        action_taken="LATCH_CLEARED",
                    )
                    self._generator.resume_after_reset()
                    self._estop_Cooldown_ticks = 0
                time.sleep(interval)
                continue

            payload = self._generator.next_payload(source="BG_GENERATOR")
            sample = TelemetrySample(**payload)
            result = self._pipeline.process_uplink(sample)

            with self._lock:
                self._packets_sent += 1

            if result["hot_path"]["emergency_stop"]:
                self._generator.notify_emergency_stop()
                self._estop_Cooldown_ticks = 0

            time.sleep(interval)


def run_cli_generator(
    base_url: str,
    interval_sec: float,
    anomaly_prob: float,
    count: int,
    auto_reset: bool,
) -> int:
    """
    Önálló folyamatként futó Python Uplink Adatgenerátor.
    HTTP POST kérésekkel küldi a telemetriát a FastAPI /api/telemetry végpontra,
    és azonnal reagál a Hot Path által visszaküldött 'STOP\\n' vészleállításra.
    """
    if httpx is None:
        print("HIBA: A 'httpx' csomag nem érhető el. Telepítsd: pip install httpx", file=sys.stderr)
        return 1

    telemetry_endpoint = f"{base_url.rstrip('/')}/api/telemetry"
    reset_endpoint = f"{base_url.rstrip('/')}/api/emergency-reset"
    gen = SumoRobotTelemetryGenerator(anomaly_probability=anomaly_prob)

    print("=" * 78)
    print("🤖 MINI SUMO ROBOT – PYTHON UPLINK TELEMETRIA GENERÁTOR")
    print(f"📡 Célpont API : {telemetry_endpoint}")
    print(f"⏱️  Ciklusidő   : {interval_sec:.2f} mp | Anomália esély: {int(anomaly_prob * 100)}%")
    print("=" * 78)

    sent = 0
    with httpx.Client(timeout=5.0) as client:
        try:
            while count <= 0 or sent < count:
                payload = gen.next_payload(source="CLI_GENERATOR")
                resp = client.post(telemetry_endpoint, json=payload)
                resp.raise_for_status()
                body = resp.json()
                sent += 1

                hp = body["hot_path"]
                status = hp["validation_status"]
                latency = hp["hot_path_latency_ms"]
                tel_id = body["telemetry_id"]

                if hp["emergency_stop"]:
                    gen.notify_emergency_stop()
                    rules = ", ".join(v["rule_code"] for v in hp["violations"])
                    cmd_repr = repr(hp["downlink_command"])
                    print(
                        f"[#{tel_id:04d}] 🔥 HOT PATH VÉSZLEÁLLÍTÁS! | Szabály: {rules} | "
                        f"Beérkező PWM=({payload['pwm_l']},{payload['pwm_r']}) VBat={payload['vbat']:.2f}V "
                        f"Dist=({payload['dist_l']},{payload['dist_r']})cm | "
                        f"Parancs a gépnek: {cmd_repr} ({latency:.3f} ms)"
                    )
                    if auto_reset and (count <= 0 or sent < count):
                        time.sleep(max(interval_sec, 1.0))
                        client.post(
                            reset_endpoint,
                            json={"operator_note": "CLI generátor automatikus újraindítás vészfékezés után"},
                        )
                        gen.resume_after_reset()
                        print("       ✅ Vészleállítás nyugtázva (motorok lehűltek), adatgenerálás folytatódik...")
                elif status == "WARNING":
                    rules = ", ".join(v["rule_code"] for v in hp["violations"])
                    print(
                        f"[#{tel_id:04d}] ⚠️  COLD PATH (WARNING: {rules}) | "
                        f"State={payload['state']:<8} VBat={payload['vbat']:.2f}V "
                        f"Dist=({payload['dist_l']:>2},{payload['dist_r']:>2})cm "
                        f"PWM=({payload['pwm_l']:>4},{payload['pwm_r']:>4})"
                    )
                else:
                    print(
                        f"[#{tel_id:04d}] ❄️  COLD PATH (OK) | "
                        f"State={payload['state']:<8} VBat={payload['vbat']:.2f}V "
                        f"Dist=({payload['dist_l']:>2},{payload['dist_r']:>2})cm "
                        f"PWM=({payload['pwm_l']:>4},{payload['pwm_r']:>4}) "
                        f"Temp={payload['motor_temp_c']:.1f}°C"
                    )

                if count <= 0 or sent < count:
                    time.sleep(interval_sec)

        except KeyboardInterrupt:
            print("\n🛑 Adatgenerátor leállítva (Ctrl+C).")
        except httpx.HTTPError as exc:
            print(f"\n❌ Hálózati hiba a FastAPI szerver elérésekor ({telemetry_endpoint}): {exc}", file=sys.stderr)
            return 2

    print(f"Összesen kiküldött és feldolgozott Uplink csomag: {sent} db.")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Mini Sumo Robot Uplink Telemetria Generátor")
    parser.add_argument("--url", default="http://127.0.0.1:8000", help="FastAPI szerver alap URL-je")
    parser.add_argument("--interval", type=float, default=0.5, help="Küldési időköz másodpercben (alap: 0.5)")
    parser.add_argument("--anomaly-prob", type=float, default=0.18, help="Kritikus anomália esélye 0..1 között")
    parser.add_argument("--count", type=int, default=0, help="Küldendő csomagok száma (0 = végtelen)")
    parser.add_argument(
        "--no-auto-reset",
        action="store_true",
        help="Ne oldja fel automatikusan a vészleállítási reteszt Hot Path esemény után",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    raise SystemExit(
        run_cli_generator(
            base_url=args.url,
            interval_sec=args.interval,
            anomaly_prob=args.anomaly_prob,
            count=args.count,
            auto_reset=not args.no_auto_reset,
        )
    )
