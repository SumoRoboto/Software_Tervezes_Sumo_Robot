from pydantic import BaseModel
from typing import List, Optional, Literal

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
