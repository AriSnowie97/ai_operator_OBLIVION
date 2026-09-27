import os
import json
import asyncio
from contextlib import asynccontextmanager
from typing import Dict, Any, Optional
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, Query, Body, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse

from core.config import config
from core.models import UserRole, CameraStatus
from core.camera_controller import camera_controller
from core.policy_engine import policy_engine
from core.audit_logger import audit_logger

WEB_DIR = Path(__file__).resolve().parent.parent / "web"

@asynccontextmanager
async def lifespan(app: FastAPI):
    await audit_logger.init_db()
    await audit_logger.prune_old_logs(days_retention=7)
    print(f"[Core Server] Initialized database and loaded {len(config.presets)} presets, {len(config.blacklist_zones)} blacklist zones.")
    yield
    print("[Core Server] Shutting down.")

app = FastAPI(
    title="Minecraft AI Camera Operator Core",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

def get_user_role(auth_token: Optional[str] = None, role_header: Optional[str] = None) -> UserRole:
    if auth_token == config.secret_token or role_header == "admin":
        return UserRole.ADMIN
    if role_header == "streamer":
        return UserRole.STREAMER
    if role_header == "ai_agent":
        return UserRole.AI_AGENT
    return UserRole.STREAMER

@app.get("/api/status")
async def get_status():
    return camera_controller.get_status()

@app.post("/api/move")
async def move_camera(
    body: Dict[str, Any] = Body(...),
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    result = await camera_controller.move_camera(
        x=float(body.get("x", 0.0)),
        y=float(body.get("y", 100.0)),
        z=float(body.get("z", 0.0)),
        pitch=float(body["pitch"]) if "pitch" in body and body["pitch"] is not None else None,
        yaw=float(body["yaw"]) if "yaw" in body and body["yaw"] is not None else None,
        roll=float(body.get("roll", 0.0)),
        duration=float(body.get("duration", 1.0)),
        smoothing=body.get("smoothing", "cinematic"),
        caller_role=role,
        caller_id=f"web_{role.value}"
    )
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("message"))
    return result

@app.post("/api/orbit")
async def start_orbit(
    body: Dict[str, Any] = Body(...),
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    result = await camera_controller.start_orbit(
        center_x=float(body.get("center_x", 0.0)),
        center_y=float(body.get("center_y", 100.0)),
        center_z=float(body.get("center_z", 0.0)),
        radius=float(body.get("radius", 15.0)),
        speed_deg_sec=float(body.get("speed", 15.0)),
        height_offset=float(body.get("height_offset", 5.0)),
        caller_role=role,
        caller_id=f"web_{role.value}"
    )
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("message"))
    return result

@app.post("/api/camera/return_to_player")
async def return_to_player(
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    await camera_controller.send_to_client({"type": "RETURN_TO_PLAYER"})
    return {"success": True, "message": "Returning camera to player"}

@app.post("/api/fov")
async def set_fov(
    body: Dict[str, Any] = Body(...),
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    result = await camera_controller.set_fov(
        fov=float(body.get("fov", 70.0)),
        duration=float(body.get("duration", 0.5)),
        caller_role=role,
        caller_id=f"web_{role.value}"
    )
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("message"))
    return result

@app.get("/api/presets")
async def get_presets():
    return config.presets

@app.post("/api/presets/apply")
async def apply_preset(
    body: Dict[str, Any] = Body(...),
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    name = body.get("name", "")
    duration = float(body.get("duration", 1.5))
    result = await camera_controller.apply_preset(
        name=name,
        duration=duration,
        caller_role=role,
        caller_id=f"web_{role.value}"
    )
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("message"))
    return result

@app.post("/api/presets/save")
async def save_preset(
    body: Dict[str, Any] = Body(...),
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    name = body.get("name", "")
    description = body.get("description", "")
    result = await camera_controller.save_preset(
        name=name,
        description=description,
        caller_role=role,
        caller_id=f"web_{role.value}"
    )
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("message"))
    return result

@app.post("/api/recording/start")
async def start_recording(
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    result = await camera_controller.start_recording(caller_role=role, caller_id=f"web_{role.value}")
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("message"))
    return result

@app.post("/api/recording/stop")
async def stop_recording(
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    result = await camera_controller.stop_recording(caller_role=role, caller_id=f"web_{role.value}")
    if not result.get("success"):
        raise HTTPException(status_code=400, detail=result.get("message"))
    return result

@app.post("/api/emergency_stop")
async def emergency_stop(
    body: Dict[str, Any] = Body(default={}),
    x_token: Optional[str] = Header(None, alias="X-Auth-Token"),
    x_role: Optional[str] = Header("streamer", alias="X-Role")
):
    role = get_user_role(x_token, x_role)
    reason = body.get("reason", "Web user pressed emergency STOP-CRANE")
    result = await camera_controller.emergency_stop(reason=reason, caller_role=role, caller_id=f"web_{role.value}")
    return result

@app.post("/api/emergency_reset")
async def emergency_reset(
    body: Dict[str, Any] = Body(default={}),
    x_token: Optional[str] = Header(None, alias="X-Auth-Token")
):
    # Allow unlock from dashboard or with token
    ok, msg = policy_engine.reset_emergency_lock(UserRole.ADMIN)
    await audit_logger.log("admin", "admin_web", "emergency_reset", {}, status="allowed")
    await camera_controller.broadcast_telemetry()
    return {"success": ok, "message": msg}

@app.get("/api/logs")
async def get_logs(limit: int = Query(50, ge=1, le=200)):
    logs = await audit_logger.get_logs(limit=limit)
    return logs

@app.get("/api/blacklist")
async def get_blacklist():
    return config.blacklist_zones

@app.websocket("/ws/telemetry")
async def websocket_telemetry(websocket: WebSocket):
    await websocket.accept()
    await camera_controller.register_telemetry_listener(websocket)
    try:
        await websocket.send_text(camera_controller.get_status().model_dump_json())
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        await camera_controller.unregister_telemetry_listener(websocket)

@app.websocket("/ws/client")
async def websocket_minecraft_client(websocket: WebSocket):
    await websocket.accept()
    await camera_controller.register_client(websocket)
    try:
        while True:
            data_raw = await websocket.receive_text()
            try:
                pkt = json.loads(data_raw)
                pkt_type = pkt.get("type")
                if pkt_type == "TELEMETRY":
                    pos = camera_controller.status.position
                    pos.x = pkt.get("x", pos.x)
                    pos.y = pkt.get("y", pos.y)
                    pos.z = pkt.get("z", pos.z)
                    pos.pitch = pkt.get("pitch", pos.pitch)
                    pos.yaw = pkt.get("yaw", pos.yaw)
                    pos.roll = pkt.get("roll", pos.roll)
                    pos.fov = pkt.get("fov", pos.fov)
                    camera_controller.status.fps = pkt.get("fps", 60.0)
                    camera_controller.status.ping_ms = pkt.get("ping", 5.0)
                    await camera_controller.broadcast_telemetry()
                elif pkt_type == "RECORDING_STATE":
                    camera_controller.status.is_recording = pkt.get("recording", False)
                    await camera_controller.broadcast_telemetry()
            except Exception as e:
                print(f"[WS Client] Error handling packet: {e}")
    except WebSocketDisconnect:
        await camera_controller.unregister_client()

if WEB_DIR.exists():
    app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")

    @app.get("/")
    async def serve_index():
        index_file = WEB_DIR / "index.html"
        if index_file.exists():
            return FileResponse(str(index_file))
        return JSONResponse({"status": "AI Camera Operator Core Running", "mcp": True})
