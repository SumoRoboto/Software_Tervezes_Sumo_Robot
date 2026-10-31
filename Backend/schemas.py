from typing import Any, Dict, List, Literal, Optional, Union
from pydantic import BaseModel, Field, model_validator

# =====================================================================
# 1. KOVACS1DANI MEGLÉVŐ ANDROID SÉMÁI (100% visszafelé kompatibilis!)
# =====================================================================


class TelemetryPointIn(BaseModel):
    rel_time_ms: int
    vbat: float
    dist_l: int
    dist_r: int
    line_l: int
    line_r: int
    pwm_l: int
    pwm_r: int


class SessionUpload(BaseModel):
    session_type: Literal["MATCH", "FREE_ROAM"]
    created_at: str
    duration_ms: int
    start_vbat: float
    end_vbat: float
    notes: Optional[str] = ""
    points: List[TelemetryPointIn]


# =====================================================================
# 2. ÚJ SÉMÁK: VALÓS IDEJŰ UPLINK, RULE ENGINE (HOT/COLD PATH) & VEZÉRLÉS
# =====================================================================

ValidationStatus = Literal["OK", "WARNING", "CRITICAL"]
RobotModeLiteral = Literal["MANUAL", "AUTO"]


class TelemetryUplinkIn(BaseModel):
    """
    Beérkező (Uplink) telemetriai csomag a Python Adatgenerátortól, a Robot
    soros/Bluetooth átjárójától vagy tesztkliensektől.

    Támogatja:
    1. Kanonikus JSON mezőket (vbat, dist_l, dist_r, line_l, line_r, pwm_l, pwm_r)
    2. Webes örökölt mezőneveket (bat, s1, s2, s3, pwmA, pwmB)
    3. Nyers hardveres UART csomagot: raw_packet="TEL,<distL>,<distR>,<lineL>,<lineR>,<vBat>"
       (ProtocolParser.kt / AGENTS.md formátum)
    """

    vbat: float = Field(..., description="2S LiPo akkumulátor feszültség (V), pl. 7.84")
    dist_l: int = Field(..., description="Bal távolságszenzor (cm)")
    dist_r: int = Field(..., description="Jobb távolságszenzor (cm)")
    line_l: int = Field(0, description="Bal vonal-/szegélyérzékelő (0 = fekete, 1 = fehér szegély)")
    line_r: int = Field(0, description="Jobb vonal-/szegélyérzékelő (0 = fekete, 1 = fehér szegély)")
    pwm_l: int = Field(0, description="Bal motor PWM jel (-255..255, kiugrás esetén >255)")
    pwm_r: int = Field(0, description="Jobb motor PWM jel (-255..255, kiugrás esetén >255)")
    motor_temp_c: float = Field(30.0, description="Motor meghajtó / tekercs hőmérséklet (°C)")
    mode: RobotModeLiteral = Field("AUTO", description="Üzemmód: MANUAL vagy AUTO")
    state: str = Field("FORWARD", description="Mozgásállapot (STOP, SEARCH, ATTACK, FORWARD, stb.)")
    source: str = Field("UPLINK", description="Adatforrás azonosító (pl. GENERATOR, ROBOT_UART, MOBILE)")
    session_id: Optional[int] = Field(None, description="Opcionális aktív futam azonosító")
    timestamp: Optional[str] = Field(None, description="Opcionális ISO-8601 időbélyeg")

    @model_validator(mode="before")
    @classmethod
    def normalize_incoming_formats(cls, values: Any) -> Any:
        if not isinstance(values, dict):
            return values

        data = dict(values)

        # 1. Nyers UART protokoll csomag feldolgozása: TEL,<distL>,<distR>,<lineL>,<lineR>,<vBat>
        raw_packet = data.pop("raw_packet", None)
        if isinstance(raw_packet, str) and raw_packet.strip().upper().startswith("TEL,"):
            parts = [p.strip() for p in raw_packet.strip().split(",")]
            if len(parts) >= 6:
                data.setdefault("dist_l", int(parts[1]))
                data.setdefault("dist_r", int(parts[2]))
                data.setdefault("line_l", 1 if int(parts[3]) != 0 else 0)
                data.setdefault("line_r", 1 if int(parts[4]) != 0 else 0)
                data.setdefault("vbat", float(parts[5]))

        # 2. Webes örökölt mezőnevek (bat, s1, s2, s3, pwmA, pwmB) átfordítása
        if "vbat" not in data and "bat" in data:
            data["vbat"] = data["bat"]
        if "dist_l" not in data and "s1" in data:
            data["dist_l"] = data["s1"]
        if "dist_r" not in data and "s2" in data:
            data["dist_r"] = data["s2"]
        if "line_l" not in data and "line_r" not in data and "s3" in data:
            s3_val = int(data["s3"])
            data["line_l"] = 1 if (s3_val in (1, 3)) else 0
            data["line_r"] = 1 if (s3_val in (2, 3)) else 0
        if "pwm_l" not in data and "pwmA" in data:
            data["pwm_l"] = data["pwmA"]
        if "pwm_r" not in data and "pwmB" in data:
            data["pwm_r"] = data["pwmB"]

        if "mode" in data and isinstance(data["mode"], str):
            upper_mode = data["mode"].strip().upper()
            if upper_mode in ("MANUAL", "AUTO"):
                data["mode"] = upper_mode

        return data


class RuleViolationOut(BaseModel):
    rule_code: str
    severity: Literal["WARNING", "CRITICAL"]
    message: str
    field: str
    actual_value: Union[float, int, str]
    threshold: str


class HotPathDecisionOut(BaseModel):
    validation_status: ValidationStatus
    emergency_stop: bool
    downlink_command: Optional[str] = None
    hot_path_latency_ms: float
    violations: List[RuleViolationOut] = []


class TelemetryUplinkResponse(BaseModel):
    status: Literal["accepted", "emergency_stopped"]
    telemetry_id: int
    path_executed: Literal["COLD_PATH_ONLY", "HOT_AND_COLD_PATH"]
    hot_path: HotPathDecisionOut
    robot_state: Dict[str, Any]


class CommandIn(BaseModel):
    """Vezérlési parancs Webes vezérlőpultról vagy Mobilalkalmazásból."""

    command: Optional[str] = Field(None, description="Parancs kód: F, B, L, R, S (vagy FORWARD, BACKWARD, LEFT, RIGHT, STOP)")
    cmd: Optional[str] = Field(None, description="Alternatív parancs mező kompatibilitáshoz")
    mode: Optional[RobotModeLiteral] = Field(None, description="MANUAL vagy AUTO üzemmód")
    speed_pct: int = Field(100, ge=10, le=100, description="Sebességkorlátozó százalék (10..100%)")
    pwm_l: Optional[int] = Field(None, ge=-255, le=255, description="Közvetlen bal motor PWM (-255..255)")
    pwm_r: Optional[int] = Field(None, ge=-255, le=255, description="Közvetlen jobb motor PWM (-255..255)")


class EmergencyStopIn(BaseModel):
    reason: str = Field("Manuális operátori vészleállítás (E-STOP)", description="Vészleállítás oka")
    source: str = Field("OPERATOR", description="Kezdeményező (WEB, MOBILE, OPERATOR)")


class EmergencyResetIn(BaseModel):
    operator_note: str = Field("Vészállapot nyugtázva és feloldva", description="Nyugtázási megjegyzés")


class SimulatorConfigIn(BaseModel):
    interval_sec: float = Field(0.6, ge=0.1, le=5.0, description="Generálási időköz másodpercben")
    anomaly_probability: float = Field(0.18, ge=0.0, le=1.0, description="Kritikus anomália injektálási esély (0..1)")
