from enum import Enum
from typing import Optional, Dict, Any, List
from pydantic import BaseModel, Field
from datetime import datetime

class CameraMode(str, Enum):
    FREECAM = "freecam"
    ORBIT = "orbit"
    TRIPOD = "tripod"
    PATH = "path"
    IDLE = "idle"

class UserRole(str, Enum):
    ADMIN = "admin"
    STREAMER = "streamer"
    AI_AGENT = "ai_agent"
    VIEWER = "viewer"

class CameraPosition(BaseModel):
    x: float = 0.0
    y: float = 100.0
    z: float = 0.0
    pitch: float = Field(default=0.0, ge=-90.0, le=90.0)
    yaw: float = 0.0
    roll: float = Field(default=0.0, ge=-180.0, le=180.0)
    fov: float = Field(default=70.0, ge=10.0, le=130.0)

class CameraStatus(BaseModel):
    position: CameraPosition = Field(default_factory=CameraPosition)
    mode: CameraMode = CameraMode.IDLE
    is_recording: bool = False
    recording_time_sec: float = 0.0
    active_preset: Optional[str] = None
    active_shader: Optional[str] = "ComplementaryReimagined"
    client_connected: bool = False
    emergency_lock: bool = False
    fps: float = 60.0
    ping_ms: float = 5.0
    rate_limit_remaining: int = 100
    timestamp: str = ""

class Preset(BaseModel):
    name: str
    description: str = ""
    x: float
    y: float
    z: float
    pitch: float = 0.0
    yaw: float = 0.0
    roll: float = 0.0
    fov: float = 70.0

class MoveCommand(BaseModel):
    x: float
    y: float
    z: float
    pitch: Optional[float] = None
    yaw: Optional[float] = None
    roll: Optional[float] = 0.0
    duration_sec: float = Field(default=1.0, ge=0.0, le=60.0)
    smoothing: str = "cinematic"

class OrbitCommand(BaseModel):
    target_type: str = "point"
    target: List[float] = Field(default_factory=lambda: [0.0, 100.0, 0.0])
    target_id: Optional[str] = None
    radius: float = Field(default=15.0, ge=2.0, le=150.0)
    speed_deg_per_sec: float = Field(default=15.0, ge=-90.0, le=90.0)
    height_offset: float = Field(default=5.0, ge=-30.0, le=50.0)
    focus_target: bool = True

class SetFovCommand(BaseModel):
    fov: float = Field(ge=15.0, le=120.0)
    duration_sec: float = Field(default=0.5, ge=0.0, le=10.0)

class ShaderCommand(BaseModel):
    shaderpack: str
    profile: Optional[str] = "High"

class AuditLogEntry(BaseModel):
    id: Optional[int] = None
    timestamp: str
    caller_role: str
    caller_id: str
    action: str
    parameters: Dict[str, Any] = Field(default_factory=dict)
    status: str
    reason: Optional[str] = None
