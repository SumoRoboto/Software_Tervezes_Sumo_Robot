from abc import ABC, abstractmethod
from collections import deque
from dataclasses import dataclass, field
import threading
import time
from typing import Any, Deque, Dict, List, Optional

try:
    from Backend import database
except ImportError:
    import database  # type: ignore


EMERGENCY_STOP_COMMAND = "STOP\n"  # ProtocolParser.kt / AGENTS.md kanonikus vészleállító csomag


@dataclass(frozen=True)
class TelemetrySample:
    """Validálandó beérkező (Uplink) telemetriai minta."""

    vbat: float
    dist_l: int
    dist_r: int
    line_l: int
    line_r: int
    pwm_l: int
    pwm_r: int
    motor_temp_c: float = 30.0
    mode: str = "AUTO"
    state: str = "FORWARD"
    source: str = "UPLINK"
    session_id: Optional[int] = None
    timestamp: Optional[str] = None


@dataclass(frozen=True)
class RuleViolation:
    """Egyetlen megsértett biztonsági vagy üzemi szabály leírása."""

    rule_code: str
    severity: str  # "WARNING" | "CRITICAL"
    message: str
    field: str
    actual_value: Any
    threshold: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "rule_code": self.rule_code,
            "severity": self.severity,
            "message": self.message,
            "field": self.field,
            "actual_value": self.actual_value,
            "threshold": self.threshold,
        }


@dataclass
class EvaluationResult:
    """A Rule Engine összesített kiértékelési eredménye."""

    validation_status: str = "OK"  # "OK" | "WARNING" | "CRITICAL"
    violations: List[RuleViolation] = field(default_factory=list)
    latency_ms: float = 0.0

    @property
    def is_critical(self) -> bool:
        return self.validation_status == "CRITICAL"

    @property
    def triggered_rule_codes(self) -> List[str]:
        return [v.rule_code for v in self.violations]


# =====================================================================
# STRATEGY PATTERN: MODULÁRIS TELEMETRIAI SZABÁLYOK (Szoftver Tervezés)
# =====================================================================


class TelemetryRule(ABC):
    """Absztrakt ősosztály minden telemetriai ellenőrző szabályhoz."""

    @abstractmethod
    def evaluate(self, sample: TelemetrySample) -> List[RuleViolation]:
        """Kiértékeli a mintát, és visszaadja az esetleges szabálysértéseket."""


class SensorSanityRule(TelemetryRule):
    """Fizikailag lehetetlen vagy zárlatos szenzorértékek szűrése (Hot Path CRITICAL)."""

    def evaluate(self, sample: TelemetrySample) -> List[RuleViolation]:
        violations: List[RuleViolation] = []
        if sample.vbat < 1.0 or sample.vbat > 10.0:
            violations.append(
                RuleViolation(
                    rule_code="SENSOR_HARDWARE_FAULT",
                    severity="CRITICAL",
                    message=f"Érvénytelen feszültségmérés ({sample.vbat:.2f}V) – zárlat vagy feszültségosztó hiba!",
                    field="vbat",
                    actual_value=round(sample.vbat, 2),
                    threshold="1.0V .. 10.0V",
                )
            )
        if sample.dist_l < 0 or sample.dist_l > 400 or sample.dist_r < 0 or sample.dist_r > 400:
            bad_val = sample.dist_l if (sample.dist_l < 0 or sample.dist_l > 400) else sample.dist_r
            violations.append(
                RuleViolation(
                    rule_code="SENSOR_HARDWARE_FAULT",
                    severity="CRITICAL",
                    message=f"Szenzor tartományon kívüli távolságérték ({bad_val} cm)!",
                    field="dist_l/dist_r",
                    actual_value=bad_val,
                    threshold="0 .. 400 cm",
                )
            )
        if sample.line_l not in (0, 1) or sample.line_r not in (0, 1):
            violations.append(
                RuleViolation(
                    rule_code="SENSOR_HARDWARE_FAULT",
                    severity="CRITICAL",
                    message=f"Érvénytelen vonalérzékelő állapot (L={sample.line_l}, R={sample.line_r})!",
                    field="line_l/line_r",
                    actual_value=f"{sample.line_l},{sample.line_r}",
                    threshold="0 vagy 1",
                )
            )
        return violations


class MotorOverloadRule(TelemetryRule):
    """
    Motor PWM kiugrások és túlmelegedés ellenőrzése:
    - CRITICAL (Hot Path -> STOP): |PWM| > 255 (vezérlő kiugrás) vagy hőmérséklet >= 75°C
    - WARNING: magas csúcsterhelés (|PWM| >= 240) vagy emelkedett hőmérséklet (>= 60°C)
    """

    def __init__(
        self,
        max_safe_pwm: int = 255,
        high_load_pwm: int = 240,
        critical_temp_c: float = 75.0,
        warning_temp_c: float = 60.0,
    ) -> None:
        self.max_safe_pwm = max_safe_pwm
        self.high_load_pwm = high_load_pwm
        self.critical_temp_c = critical_temp_c
        self.warning_temp_c = warning_temp_c

    def evaluate(self, sample: TelemetrySample) -> List[RuleViolation]:
        violations: List[RuleViolation] = []
        peak_pwm = max(abs(sample.pwm_l), abs(sample.pwm_r))

        if peak_pwm > self.max_safe_pwm:
            violations.append(
                RuleViolation(
                    rule_code="MOTOR_PWM_SPIKE",
                    severity="CRITICAL",
                    message=(
                        f"Kritikus motor PWM tüske észlelve (L={sample.pwm_l}, R={sample.pwm_r})! "
                        f"Meghaladja a hardveres ±{self.max_safe_pwm} határt."
                    ),
                    field="pwm_l/pwm_r",
                    actual_value=peak_pwm,
                    threshold=f"<= {self.max_safe_pwm}",
                )
            )
        elif peak_pwm >= self.high_load_pwm:
            violations.append(
                RuleViolation(
                    rule_code="MOTOR_HIGH_LOAD",
                    severity="WARNING",
                    message=f"Magas motorterhelés csúcs (PWM={peak_pwm}).",
                    field="pwm_l/pwm_r",
                    actual_value=peak_pwm,
                    threshold=f"< {self.high_load_pwm}",
                )
            )

        if sample.motor_temp_c >= self.critical_temp_c:
            violations.append(
                RuleViolation(
                    rule_code="MOTOR_OVERHEAT_CRITICAL",
                    severity="CRITICAL",
                    message=f"Kritikus motor/meghajtó túlmelegedés ({sample.motor_temp_c:.1f}°C)!",
                    field="motor_temp_c",
                    actual_value=round(sample.motor_temp_c, 1),
                    threshold=f"< {self.critical_temp_c}°C",
                )
            )
        elif sample.motor_temp_c >= self.warning_temp_c:
            violations.append(
                RuleViolation(
                    rule_code="MOTOR_TEMP_WARNING",
                    severity="WARNING",
                    message=f"Emelkedett motor hőmérséklet ({sample.motor_temp_c:.1f}°C).",
                    field="motor_temp_c",
                    actual_value=round(sample.motor_temp_c, 1),
                    threshold=f"< {self.warning_temp_c}°C",
                )
            )

        return violations


class CollisionDangerRule(TelemetryRule):
    """
    Ütközésveszély / blokkolás elleni védelem:
    - CRITICAL (Hot Path -> STOP): akadály <= 5 cm-en belül nagy előremeneti sebességnél (PWM > 160)
    - WARNING: akadály <= 12 cm-en belül közepes/nagy sebességnél (PWM > 120)
    """

    def __init__(self, critical_dist_cm: int = 5, warning_dist_cm: int = 12) -> None:
        self.critical_dist_cm = critical_dist_cm
        self.warning_dist_cm = warning_dist_cm

    def evaluate(self, sample: TelemetrySample) -> List[RuleViolation]:
        if sample.dist_l <= 0 or sample.dist_r <= 0:
            return []

        nearest_cm = min(sample.dist_l, sample.dist_r)
        forward_pwm = max(sample.pwm_l, sample.pwm_r)

        if nearest_cm <= self.critical_dist_cm and forward_pwm > 160:
            return [
                RuleViolation(
                    rule_code="COLLISION_IMMINENT",
                    severity="CRITICAL",
                    message=(
                        f"Közvetlen ütközésveszély ({nearest_cm} cm) nagy sebességnél "
                        f"(PWM={forward_pwm})! Azonnali vészfékezés szükséges."
                    ),
                    field="dist_l/dist_r",
                    actual_value=nearest_cm,
                    threshold=f"> {self.critical_dist_cm} cm @ PWM > 160",
                )
            ]

        if nearest_cm <= self.warning_dist_cm and forward_pwm > 120:
            return [
                RuleViolation(
                    rule_code="OBSTACLE_PROXIMITY",
                    severity="WARNING",
                    message=f"Közeli objektum a zónában ({nearest_cm} cm, PWM={forward_pwm}).",
                    field="dist_l/dist_r",
                    actual_value=nearest_cm,
                    threshold=f"> {self.warning_dist_cm} cm",
                )
            ]

        return []


class DohyoEdgeSafetyRule(TelemetryRule):
    """
    Dohyo pályaelhagyás / leesés elleni védelem:
    - CRITICAL (Hot Path -> STOP): fehér Tawara vonal (line_l=1 vagy line_r=1), miközben mindkét motor előre hajt (> 80)
    - WARNING: vonalérintés álló/tolató/forduló állapotban
    """

    def evaluate(self, sample: TelemetrySample) -> List[RuleViolation]:
        border_hit = sample.line_l == 1 or sample.line_r == 1
        if not border_hit:
            return []

        if sample.pwm_l > 80 and sample.pwm_r > 80:
            return [
                RuleViolation(
                    rule_code="DOHYO_FALL_DANGER",
                    severity="CRITICAL",
                    message=(
                        f"Dohyo fehér szegély érzékelve (L={sample.line_l}, R={sample.line_r}) "
                        f"aktív előrehaladás közben (PWM={sample.pwm_l}/{sample.pwm_r}) – leesésveszély!"
                    ),
                    field="line_l/line_r",
                    actual_value=f"L:{sample.line_l},R:{sample.line_r}",
                    threshold="PWM <= 0 szegélyérintéskor",
                )
            ]

        return [
            RuleViolation(
                rule_code="DOHYO_BORDER_DETECTED",
                severity="WARNING",
                message=f"Dohyo fehér szegély érintés (L={sample.line_l}, R={sample.line_r}).",
                field="line_l/line_r",
                actual_value=f"L:{sample.line_l},R:{sample.line_r}",
                threshold="0 (Fekete küzdőtér)",
            )
        ]


class BatteryProtectionRule(TelemetryRule):
    """
    2S LiPo akkumulátor védelem (BatteryState.kt küszöbértékeivel összhangban):
    - CRITICAL (Hot Path -> STOP): vbat < 6.4V (mélykisülés / cellakárosodás veszélye)
    - WARNING: 6.4V <= vbat < 6.8V (kritikus töltöttségi szint figyelmeztetés)
    """

    def __init__(self, cutoff_voltage: float = 6.4, warning_voltage: float = 6.8) -> None:
        self.cutoff_voltage = cutoff_voltage
        self.warning_voltage = warning_voltage

    def evaluate(self, sample: TelemetrySample) -> List[RuleViolation]:
        if sample.vbat < 1.0:
            # A SensorSanityRule kezeli a <1.0V hardverhibát
            return []

        if sample.vbat < self.cutoff_voltage:
            return [
                RuleViolation(
                    rule_code="BATTERY_DEEP_DISCHARGE",
                    severity="CRITICAL",
                    message=(
                        f"Kritikus 2S LiPo mélykisülés ({sample.vbat:.2f}V < {self.cutoff_voltage}V)! "
                        "Motorok azonnali lekapcsolása az akku védelmében."
                    ),
                    field="vbat",
                    actual_value=round(sample.vbat, 2),
                    threshold=f">= {self.cutoff_voltage}V",
                )
            ]

        if sample.vbat < self.warning_voltage:
            return [
                RuleViolation(
                    rule_code="BATTERY_LOW_WARNING",
                    severity="WARNING",
                    message=f"Alacsony akkumulátor feszültség ({sample.vbat:.2f}V < {self.warning_voltage}V).",
                    field="vbat",
                    actual_value=round(sample.vbat, 2),
                    threshold=f">= {self.warning_voltage}V",
                )
            ]

        return []


class RuleEngine:
    """
    Mentés előtti ellenőrző motor (Rule Engine).
    Memóriában, szub-milliszekundumos késleltetéssel futtatja le a regisztrált szabályokat.
    """

    def __init__(self, rules: Optional[List[TelemetryRule]] = None) -> None:
        self.rules: List[TelemetryRule] = rules or [
            SensorSanityRule(),
            MotorOverloadRule(),
            CollisionDangerRule(),
            DohyoEdgeSafetyRule(),
            BatteryProtectionRule(),
        ]

    def evaluate(self, sample: TelemetrySample) -> EvaluationResult:
        start_ns = time.perf_counter_ns()
        violations: List[RuleViolation] = []

        for rule in self.rules:
            violations.extend(rule.evaluate(sample))

        has_critical = any(v.severity == "CRITICAL" for v in violations)
        has_warning = any(v.severity == "WARNING" for v in violations)

        if has_critical:
            status = "CRITICAL"
        elif has_warning:
            status = "WARNING"
        else:
            status = "OK"

        elapsed_ms = round((time.perf_counter_ns() - start_ns) / 1_000_000.0, 4)
        return EvaluationResult(validation_status=status, violations=violations, latency_ms=elapsed_ms)


# =====================================================================
# ROBOT ÁLLAPOTGÉP, VÉSZLEÁLLÍTÓ DISZPÉCSER & HOT/COLD PATH PIPELINE
# =====================================================================


class RobotControllerState:
    """
    Szálbiztos (thread-safe) központi robotállapot és Downlink parancs-diszpécser.
    A Hot Path közvetlenül ide küldi a `STOP\\n` vészleállítást még az adatbázis-művelet előtt.
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()
        iso_now, _ = database.utc_now_iso_and_epoch()
        self._state: Dict[str, Any] = {
            "mode": "MANUAL",
            "state": "STOP",
            "vbat": 8.40,
            "dist_l": 35,
            "dist_r": 42,
            "line_l": 0,
            "line_r": 0,
            "pwm_l": 0,
            "pwm_r": 0,
            "motor_temp_c": 26.5,
            "validation_status": "OK",
            "triggered_rules": [],
            "emergency_stop": False,
            "emergency_reason": None,
            "emergency_count": 0,
            "last_downlink_command": None,
            "updated_at": iso_now,
        }
        self._downlink_outbox: Deque[Dict[str, Any]] = deque(maxlen=100)

    def dispatch_emergency_stop(self, reason: str, rule_codes: List[str]) -> str:
        """
        HOT PATH: Azonnali hardveres vészleállítás kiadása ('STOP\\n'),
        motor PWM jelek nullázása és biztonsági retesz (latch) aktiválása.
        """
        iso_now, epoch_ms = database.utc_now_iso_and_epoch()
        with self._lock:
            self._state["state"] = "EMERGENCY_STOP"
            self._state["pwm_l"] = 0
            self._state["pwm_r"] = 0
            self._state["emergency_stop"] = True
            self._state["emergency_reason"] = reason
            self._state["emergency_count"] += 1
            self._state["validation_status"] = "CRITICAL"
            self._state["triggered_rules"] = list(rule_codes)
            self._state["last_downlink_command"] = EMERGENCY_STOP_COMMAND
            self._state["updated_at"] = iso_now

            self._downlink_outbox.append(
                {
                    "timestamp": iso_now,
                    "epoch_ms": epoch_ms,
                    "command": EMERGENCY_STOP_COMMAND,
                    "priority": "HOT_PATH_CRITICAL",
                    "reason": reason,
                }
            )
        return EMERGENCY_STOP_COMMAND

    def clear_emergency_latch(self, note: str = "Operator reset") -> Dict[str, Any]:
        """Feloldja a vészleállítási reteszt, a robot biztonságos STOP állapotban marad."""
        iso_now, epoch_ms = database.utc_now_iso_and_epoch()
        with self._lock:
            self._state["emergency_stop"] = False
            self._state["emergency_reason"] = None
            self._state["state"] = "STOP"
            self._state["pwm_l"] = 0
            self._state["pwm_r"] = 0
            self._state["validation_status"] = "OK"
            self._state["triggered_rules"] = []
            self._state["last_downlink_command"] = "RESET_OK"
            self._state["updated_at"] = iso_now

            self._downlink_outbox.append(
                {
                    "timestamp": iso_now,
                    "epoch_ms": epoch_ms,
                    "command": "STOP\n",
                    "priority": "NORMAL",
                    "reason": note,
                }
            )
            return self.get_unified_snapshot()

    def update_from_validated_telemetry(
        self, sample: TelemetrySample, eval_result: EvaluationResult
    ) -> Dict[str, Any]:
        """
        Frissíti az élő szenzorértékeket. Ha a Hot Path vészleállítás aktív,
        a motorok kimenete szigorúan 0 / EMERGENCY_STOP marad!
        """
        iso_now = sample.timestamp or database.utc_now_iso_and_epoch()[0]
        with self._lock:
            self._state["vbat"] = round(sample.vbat, 2)
            self._state["dist_l"] = sample.dist_l
            self._state["dist_r"] = sample.dist_r
            self._state["line_l"] = sample.line_l
            self._state["line_r"] = sample.line_r
            self._state["motor_temp_c"] = round(sample.motor_temp_c, 1)
            self._state["mode"] = sample.mode
            self._state["updated_at"] = iso_now

            if self._state["emergency_stop"] or eval_result.is_critical:
                # Vészleállítás alatt a gép motorjai nem foroghatnak
                self._state["state"] = "EMERGENCY_STOP"
                self._state["pwm_l"] = 0
                self._state["pwm_r"] = 0
            else:
                self._state["state"] = sample.state
                self._state["pwm_l"] = sample.pwm_l
                self._state["pwm_r"] = sample.pwm_r
                self._state["validation_status"] = eval_result.validation_status
                self._state["triggered_rules"] = eval_result.triggered_rule_codes

            return self.get_unified_snapshot()

    def apply_operator_command(
        self,
        *,
        state: Optional[str] = None,
        pwm_l: Optional[int] = None,
        pwm_r: Optional[int] = None,
        mode: Optional[str] = None,
        uart_packet: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Manuális parancs (F, B, L, R, S, MODE) érvényesítése a robot állapotgépen."""
        iso_now, epoch_ms = database.utc_now_iso_and_epoch()
        with self._lock:
            if mode in ("MANUAL", "AUTO"):
                self._state["mode"] = mode

            if state == "STOP":
                # A STOP parancs egyben nyugtázza a vészhelyzeti reteszt is
                self._state["emergency_stop"] = False
                self._state["emergency_reason"] = None
                self._state["validation_status"] = "OK"
                self._state["triggered_rules"] = []
                self._state["state"] = "STOP"
                self._state["pwm_l"] = 0
                self._state["pwm_r"] = 0
            else:
                if self._state["emergency_stop"]:
                    raise RuntimeError(
                        f"Vészleállítási retesz aktív ({self._state['emergency_reason']})! "
                        "Előbb küldj STOP ('S') parancsot vagy hívd a /api/emergency-reset végpontot."
                    )
                if state is not None:
                    self._state["state"] = state
                if pwm_l is not None:
                    self._state["pwm_l"] = pwm_l
                if pwm_r is not None:
                    self._state["pwm_r"] = pwm_r

            if uart_packet:
                self._state["last_downlink_command"] = uart_packet
                self._downlink_outbox.append(
                    {
                        "timestamp": iso_now,
                        "epoch_ms": epoch_ms,
                        "command": uart_packet,
                        "priority": "OPERATOR",
                        "reason": f"State -> {self._state['state']}",
                    }
                )

            self._state["updated_at"] = iso_now
            return self.get_unified_snapshot()

    def get_recent_downlink_commands(self, limit: int = 20) -> List[Dict[str, Any]]:
        with self._lock:
            items = list(self._downlink_outbox)
        return list(reversed(items))[:limit]

    def get_unified_snapshot(self) -> Dict[str, Any]:
        """
        Egységesített JSON állapot mind a Mobil App (vbat, dist_l, pwm_l...),
        mind a Webes Vezérlőpult (bat, s1, s2, s3, pwmA, pwmB) számára!
        """
        with self._lock:
            vbat = float(self._state["vbat"])
            bat_pct = database.calculate_battery_percent(vbat)
            line_l = int(self._state["line_l"])
            line_r = int(self._state["line_r"])
            s3_bitmask = (1 if line_l else 0) | (2 if line_r else 0)
            pwm_l = int(self._state["pwm_l"])
            pwm_r = int(self._state["pwm_r"])

            return {
                # Kanonikus mezők (FastAPI + Android App)
                "mode": self._state["mode"],
                "state": self._state["state"],
                "vbat": vbat,
                "battery_pct": bat_pct,
                "dist_l": self._state["dist_l"],
                "dist_r": self._state["dist_r"],
                "line_l": line_l,
                "line_r": line_r,
                "pwm_l": pwm_l,
                "pwm_r": pwm_r,
                "motor_temp_c": self._state["motor_temp_c"],
                "validation_status": self._state["validation_status"],
                "triggered_rules": list(self._state["triggered_rules"]),
                "emergency_stop": bool(self._state["emergency_stop"]),
                "emergency_reason": self._state["emergency_reason"],
                "emergency_count": int(self._state["emergency_count"]),
                "last_downlink_command": self._state["last_downlink_command"],
                "updated_at": self._state["updated_at"],
                # Webes Dashboard visszafelé kompatibilis mezők (src/web/index.html)
                "bat": vbat,
                "s1": self._state["dist_l"],
                "s2": self._state["dist_r"],
                "s3": s3_bitmask,
                "pwmA": abs(pwm_l),
                "pwmB": abs(pwm_r),
            }


class TelemetryPipeline:
    """
    A Hot Path és Cold Path adatfolyamot összehangoló központi szolgáltatás:
    1. Beérkező Uplink minta ellenőrzése a Rule Engine-nel (mentés előtt!)
    2. HOT PATH: ha az érték CRITICAL -> AZONNALI vészleállítás ('STOP\\n') a gépnek (még DB I/O előtt!)
    3. COLD PATH: ha az érték megfelelő (vagy miután a vészleállítás megtörtént) -> SQLite naplózás
    """

    def __init__(
        self,
        rule_engine: Optional[RuleEngine] = None,
        robot_controller: Optional[RobotControllerState] = None,
    ) -> None:
        self.rule_engine = rule_engine or RuleEngine()
        self.robot_controller = robot_controller or RobotControllerState()

    def process_uplink(self, sample: TelemetrySample) -> Dict[str, Any]:
        # 1. LÉPÉS: Mentés előtti ellenőrzés (Rule Engine - memóriában)
        eval_result = self.rule_engine.evaluate(sample)

        downlink_command: Optional[str] = None
        emergency_triggered = False

        # 2. LÉPÉS: HOT PATH – Kritikus érték esetén azonnali vészleállítás (DB írás ELŐTT!)
        if eval_result.is_critical:
            emergency_triggered = True
            critical_messages = "; ".join(
                v.message for v in eval_result.violations if v.severity == "CRITICAL"
            )
            downlink_command = self.robot_controller.dispatch_emergency_stop(
                reason=critical_messages,
                rule_codes=eval_result.triggered_rule_codes,
            )

        # Élő robotállapot frissítése (kritikus esetben a motorok már 0-ra vannak zárva)
        live_state = self.robot_controller.update_from_validated_telemetry(sample, eval_result)

        # 3. LÉPÉS: COLD PATH – Validált telemetria és biztonsági események naplózása SQLite-ba
        iso_now, epoch_ms = database.utc_now_iso_and_epoch()
        if sample.timestamp:
            parsed_epoch = database.parse_iso_to_epoch_ms(sample.timestamp)
            if parsed_epoch is not None:
                iso_now = sample.timestamp
                epoch_ms = parsed_epoch

        bat_pct = database.calculate_battery_percent(sample.vbat)

        events_to_persist: List[Dict[str, Any]] = []
        raw_snapshot = {
            "vbat": round(sample.vbat, 2),
            "dist_l": sample.dist_l,
            "dist_r": sample.dist_r,
            "line_l": sample.line_l,
            "line_r": sample.line_r,
            "pwm_l": sample.pwm_l,
            "pwm_r": sample.pwm_r,
            "motor_temp_c": round(sample.motor_temp_c, 1),
            "mode": sample.mode,
            "state": sample.state,
            "source": sample.source,
        }

        for violation in eval_result.violations:
            events_to_persist.append(
                {
                    "severity": violation.severity,
                    "rule_code": violation.rule_code,
                    "message": violation.message,
                    "action_taken": EMERGENCY_STOP_COMMAND.strip() if violation.severity == "CRITICAL" else "LOG_WARNING",
                    "snapshot": raw_snapshot,
                }
            )

        telemetry_id = database.insert_telemetry_log_and_events(
            timestamp=iso_now,
            epoch_ms=epoch_ms,
            session_id=sample.session_id,
            source=sample.source,
            mode=sample.mode,
            state=sample.state,
            vbat=sample.vbat,
            battery_pct=bat_pct,
            dist_l=sample.dist_l,
            dist_r=sample.dist_r,
            line_l=sample.line_l,
            line_r=sample.line_r,
            pwm_l=sample.pwm_l,
            pwm_r=sample.pwm_r,
            motor_temp_c=sample.motor_temp_c,
            validation_status=eval_result.validation_status,
            triggered_rules=eval_result.triggered_rule_codes,
            emergency_stopped=emergency_triggered,
            events_to_log=events_to_persist,
        )

        return {
            "status": "emergency_stopped" if emergency_triggered else "accepted",
            "telemetry_id": telemetry_id,
            "path_executed": "HOT_AND_COLD_PATH" if emergency_triggered else "COLD_PATH_ONLY",
            "hot_path": {
                "validation_status": eval_result.validation_status,
                "emergency_stop": emergency_triggered,
                "downlink_command": downlink_command,
                "hot_path_latency_ms": eval_result.latency_ms,
                "violations": [v.to_dict() for v in eval_result.violations],
            },
            "robot_state": live_state,
        }
