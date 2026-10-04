import sys
import asyncio
import json
import math
import time
from typing import Optional, Dict, Any, List, Set
from fastapi import WebSocket
from datetime import datetime, timezone

from core.config import config
from core.models import CameraPosition, CameraStatus, CameraMode, Preset, UserRole
from core.policy_engine import policy_engine
from core.obs_client import obs_client

class CameraController:
    def __init__(self):
        self.status = CameraStatus()
        self.status.position = CameraPosition(x=0.0, y=100.0, z=0.0, pitch=15.0, yaw=0.0, roll=0.0, fov=70.0)
        self.status.active_preset = "safe_spawn"
        
        self.client_socket: Optional[WebSocket] = None
        self.telemetry_listeners: Set[WebSocket] = set()
        
        self.recording_start_time: Optional[float] = None
        self.orbit_task: Optional[asyncio.Task] = None
        self.interpolation_task: Optional[asyncio.Task] = None
        
        # Subscribe to OBS Studio record state changes
        obs_client.on_record_state_change = self._on_obs_record_state

    def get_status(self) -> CameraStatus:
        self.status.emergency_lock = policy_engine.is_locked()
        self.status.client_connected = self.client_socket is not None
        self.status.obs_connected = obs_client.is_connected
        if self.status.is_recording and self.recording_start_time:
            self.status.recording_time_sec = round(time.time() - self.recording_start_time, 1)
        else:
            self.status.recording_time_sec = 0.0
        self.status.timestamp = datetime.now(timezone.utc).isoformat()
        return self.status

    async def _on_obs_record_state(self, active: bool):
        if active and not self.status.is_recording:
            self.status.is_recording = True
            self.recording_start_time = time.time()
            await self.send_to_client({"type": "START_RECORDING"})
            await self.broadcast_telemetry()
        elif not active and self.status.is_recording:
            self.status.is_recording = False
            self.recording_start_time = None
            await self.send_to_client({"type": "STOP_RECORDING"})
            await self.broadcast_telemetry()

    async def register_client(self, websocket: WebSocket):
        self.client_socket = websocket
        self.status.client_connected = True
        print("[CameraController] Minecraft GPU client connected via WebSocket.", file=sys.stderr)
    async def unregister_client(self):
        self.client_socket = None
        self.status.client_connected = False
        print("[CameraController] Minecraft GPU client disconnected.", file=sys.stderr)
    async def register_telemetry_listener(self, websocket: WebSocket):
        self.telemetry_listeners.add(websocket)

    async def unregister_telemetry_listener(self, websocket: WebSocket):
        self.telemetry_listeners.discard(websocket)

    async def broadcast_telemetry(self):
        if not self.telemetry_listeners:
            return
        payload = self.get_status().model_dump_json()
        disconnected = set()
        for ws in self.telemetry_listeners:
            try:
                await ws.send_text(payload)
            except Exception:
                disconnected.add(ws)
        for ws in disconnected:
            self.telemetry_listeners.discard(ws)

    async def send_to_client(self, message: Dict[str, Any]):
        if self.client_socket:
            try:
                await self.client_socket.send_text(json.dumps(message))
            except Exception as e:
                print(f"[CameraController] Failed to dispatch packet to Minecraft client: {e}", file=sys.stderr)
    async def _interpolate_to(self, target_pos: CameraPosition, duration: float):
        start_pos = self.status.position.model_copy()
        steps = max(1, int(duration * 30))
        sleep_interval = duration / steps

        for step in range(1, steps + 1):
            if policy_engine.is_locked() and self.status.mode != CameraMode.IDLE:
                break
            t = step / steps
            ease_t = 4 * t * t * t if t < 0.5 else 1 - math.pow(-2 * t + 2, 3) / 2

            self.status.position.x = start_pos.x + (target_pos.x - start_pos.x) * ease_t
            self.status.position.y = start_pos.y + (target_pos.y - start_pos.y) * ease_t
            self.status.position.z = start_pos.z + (target_pos.z - start_pos.z) * ease_t
            self.status.position.pitch = start_pos.pitch + (target_pos.pitch - start_pos.pitch) * ease_t
            self.status.position.yaw = start_pos.yaw + (target_pos.yaw - start_pos.yaw) * ease_t
            self.status.position.roll = start_pos.roll + (target_pos.roll - start_pos.roll) * ease_t
            self.status.position.fov = start_pos.fov + (target_pos.fov - start_pos.fov) * ease_t
            
            await self.broadcast_telemetry()
            await asyncio.sleep(sleep_interval)

        self.status.position = target_pos
        await self.broadcast_telemetry()

    async def move_camera(
        self,
        x: float,
        y: float,
        z: float,
        pitch: Optional[float] = None,
        yaw: Optional[float] = None,
        roll: Optional[float] = None,
        duration: float = 1.0,
        smoothing: str = "cinematic",
        caller_role: UserRole = UserRole.STREAMER,
        caller_id: str = "system"
    ) -> Dict[str, Any]:
        params = {
            "x": x, "y": y, "z": z,
            "pitch": pitch if pitch is not None else self.status.position.pitch,
            "yaw": yaw if yaw is not None else self.status.position.yaw,
            "roll": roll if roll is not None else self.status.position.roll,
            "duration_sec": duration,
            "smoothing": smoothing
        }
        
        ok, msg, validated_params = await policy_engine.validate_action(
            caller_role, caller_id, "move_camera", params, self.status.position
        )
        if not ok:
            return {"success": False, "message": msg}

        if self.orbit_task and not self.orbit_task.done():
            self.orbit_task.cancel()

        target_pos = CameraPosition(
            x=validated_params["x"],
            y=validated_params["y"],
            z=validated_params["z"],
            pitch=validated_params["pitch"],
            yaw=validated_params["yaw"],
            roll=validated_params["roll"],
            fov=self.status.position.fov
        )
        self.status.mode = CameraMode.FREECAM
        self.status.active_preset = None

        await self.send_to_client({
            "type": "MOVE",
            "x": target_pos.x, "y": target_pos.y, "z": target_pos.z,
            "pitch": target_pos.pitch, "yaw": target_pos.yaw, "roll": target_pos.roll,
            "duration": duration, "smoothing": smoothing
        })

        if duration > 0.1:
            if self.interpolation_task and not self.interpolation_task.done():
                self.interpolation_task.cancel()
            self.interpolation_task = asyncio.create_task(self._interpolate_to(target_pos, duration))
        else:
            self.status.position = target_pos
            await self.broadcast_telemetry()

        return {"success": True, "message": f"Camera moving to ({target_pos.x:.1f}, {target_pos.y:.1f}, {target_pos.z:.1f})"}

    async def start_orbit(
        self,
        center_x: float,
        center_y: float,
        center_z: float,
        radius: float = 15.0,
        speed_deg_sec: float = 20.0,
        height_offset: float = 5.0,
        caller_role: UserRole = UserRole.STREAMER,
        caller_id: str = "system"
    ) -> Dict[str, Any]:
        params = {
            "x": center_x, "y": center_y, "z": center_z,
            "radius": radius, "speed_deg_sec": speed_deg_sec, "height_offset": height_offset
        }
        ok, msg, _ = await policy_engine.validate_action(
            caller_role, caller_id, "orbit", params, self.status.position
        )
        if not ok:
            return {"success": False, "message": msg}

        if self.orbit_task and not self.orbit_task.done():
            self.orbit_task.cancel()

        self.status.mode = CameraMode.ORBIT
        await self.send_to_client({
            "type": "ORBIT",
            "center": [center_x, center_y, center_z],
            "radius": radius,
            "speed": speed_deg_sec,
            "height": height_offset
        })

        async def _orbit_loop():
            angle_deg = 0.0
            dt = 0.05
            while self.status.mode == CameraMode.ORBIT and not policy_engine.is_locked():
                angle_deg = (angle_deg + speed_deg_sec * dt) % 360.0
                rad = math.radians(angle_deg)
                
                cam_x = center_x + radius * math.cos(rad)
                cam_z = center_z + radius * math.sin(rad)
                cam_y = center_y + height_offset
                
                dx = center_x - cam_x
                dy = center_y - cam_y
                dz = center_z - cam_z
                dist_xz = math.sqrt(dx*dx + dz*dz)
                
                yaw = math.degrees(math.atan2(-dx, dz))
                pitch = math.degrees(math.atan2(-dy, dist_xz))
                
                self.status.position.x = cam_x
                self.status.position.y = cam_y
                self.status.position.z = cam_z
                self.status.position.yaw = yaw
                self.status.position.pitch = pitch
                
                await self.broadcast_telemetry()
                await asyncio.sleep(dt)

        self.orbit_task = asyncio.create_task(_orbit_loop())
        return {"success": True, "message": f"Orbit mode started around ({center_x:.1f}, {center_y:.1f}, {center_z:.1f})"}

    async def set_fov(
        self,
        fov: float,
        duration: float = 0.5,
        caller_role: UserRole = UserRole.STREAMER,
        caller_id: str = "system"
    ) -> Dict[str, Any]:
        params = {"fov": fov, "duration_sec": duration}
        ok, msg, val = await policy_engine.validate_action(
            caller_role, caller_id, "set_fov", params, self.status.position
        )
        if not ok:
            return {"success": False, "message": msg}

        target_fov = val["fov"]
        await self.send_to_client({"type": "SET_FOV", "fov": target_fov, "duration": duration})
        
        start_fov = self.status.position.fov
        steps = max(1, int(duration * 20))
        for step in range(1, steps + 1):
            t = step / steps
            self.status.position.fov = start_fov + (target_fov - start_fov) * t
            await self.broadcast_telemetry()
            await asyncio.sleep(duration / steps)

        self.status.position.fov = target_fov
        await self.broadcast_telemetry()
        return {"success": True, "message": f"FOV adjusted to {target_fov:.1f}"}

    async def apply_preset(
        self,
        name: str,
        duration: float = 1.5,
        caller_role: UserRole = UserRole.STREAMER,
        caller_id: str = "system"
    ) -> Dict[str, Any]:
        preset_dict = config.presets.get(name)
        if not preset_dict:
            return {"success": False, "message": f"Preset '{name}' not found"}

        res = await self.move_camera(
            x=preset_dict["x"],
            y=preset_dict["y"],
            z=preset_dict["z"],
            pitch=preset_dict.get("pitch", 0.0),
            yaw=preset_dict.get("yaw", 0.0),
            roll=preset_dict.get("roll", 0.0),
            duration=duration,
            smoothing="cinematic",
            caller_role=caller_role,
            caller_id=caller_id
        )
        if res.get("success"):
            self.status.active_preset = name
            if "fov" in preset_dict:
                asyncio.create_task(self.set_fov(preset_dict["fov"], duration=duration, caller_role=caller_role, caller_id=caller_id))
        return res

    async def save_preset(
        self,
        name: str,
        description: str = "",
        caller_role: UserRole = UserRole.STREAMER,
        caller_id: str = "system"
    ) -> Dict[str, Any]:
        params = {"name": name, "description": description}
        ok, msg, _ = await policy_engine.validate_action(
            caller_role, caller_id, "save_preset", params
        )
        if not ok:
            return {"success": False, "message": msg}

        pos = self.status.position
        new_preset = {
            "name": name,
            "description": description,
            "x": round(pos.x, 2),
            "y": round(pos.y, 2),
            "z": round(pos.z, 2),
            "pitch": round(pos.pitch, 2),
            "yaw": round(pos.yaw, 2),
            "roll": round(pos.roll, 2),
            "fov": round(pos.fov, 1)
        }
        presets = dict(config.presets)
        presets[name] = new_preset
        config.save_presets(presets)
        return {"success": True, "message": f"Preset '{name}' saved successfully"}

    async def start_recording(self, caller_role: UserRole = UserRole.STREAMER, caller_id: str = "system") -> Dict[str, Any]:
        ok, msg, _ = await policy_engine.validate_action(caller_role, caller_id, "start_recording", {})
        if not ok:
            return {"success": False, "message": msg}

        self.status.is_recording = True
        self.recording_start_time = time.time()
        await self.send_to_client({"type": "START_RECORDING"})
        
        obs_res = await obs_client.start_recording()
        obs_detail = f" (OBS: {obs_res.get('message', '')})" if obs_client.enabled else ""

        await self.broadcast_telemetry()
        return {"success": True, "message": f"Recording started{obs_detail}"}

    async def stop_recording(self, caller_role: UserRole = UserRole.STREAMER, caller_id: str = "system") -> Dict[str, Any]:
        ok, msg, _ = await policy_engine.validate_action(caller_role, caller_id, "stop_recording", {})
        if not ok:
            return {"success": False, "message": msg}

        self.status.is_recording = False
        duration = self.status.recording_time_sec
        self.recording_start_time = None
        await self.send_to_client({"type": "STOP_RECORDING"})
        
        obs_res = await obs_client.stop_recording()
        obs_detail = f" (OBS: {obs_res.get('message', '')})" if obs_client.enabled else ""

        await self.broadcast_telemetry()
        return {"success": True, "message": f"Recording stopped. Duration: {duration}s{obs_detail}"}

    async def set_shader(
        self,
        shaderpack: str,
        profile: str = "High",
        caller_role: UserRole = UserRole.ADMIN,
        caller_id: str = "system"
    ) -> Dict[str, Any]:
        params = {"shaderpack": shaderpack, "profile": profile}
        ok, msg, _ = await policy_engine.validate_action(caller_role, caller_id, "set_shader", params)
        if not ok:
            return {"success": False, "message": msg}

        self.status.active_shader = shaderpack
        await self.send_to_client({"type": "SET_SHADER", "shaderpack": shaderpack, "profile": profile})
        await self.broadcast_telemetry()
        return {"success": True, "message": f"Shaderpack switched to '{shaderpack}' ({profile})"}

    async def emergency_stop(self, reason: str = "Emergency stop activated", caller_role: UserRole = UserRole.STREAMER, caller_id: str = "system") -> Dict[str, Any]:
        policy_engine.trigger_emergency_stop(reason)
        if self.orbit_task and not self.orbit_task.done():
            self.orbit_task.cancel()
        if self.interpolation_task and not self.interpolation_task.done():
            self.interpolation_task.cancel()

        self.status.mode = CameraMode.FOLLOW_PLAYER
        self.status.active_preset = None
        
        await self.send_to_client({"type": "EMERGENCY_STOP", "action": "return_to_player", "reason": reason})
        await self.broadcast_telemetry()
        return {"success": True, "message": f"СТОП-КРАН активирован: Камера немедленно остановлена и возвращена игроку ({reason})"}

camera_controller = CameraController()
