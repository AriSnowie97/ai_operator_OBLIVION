import os
import sys
from pathlib import Path

# Allow `python core/mcp_server.py` to import the `core` package
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import json
import asyncio
import urllib.request
import urllib.error
from typing import Optional, Dict, Any

from mcp.server.mcpserver import MCPServer
from core.models import UserRole
from core.camera_controller import camera_controller
from core.audit_logger import audit_logger
from core.config import config

mcp = MCPServer(
    name="minecraft-camera-operator",
    description="MCP server controlling a virtual Minecraft cinematic camera operator with Iris shaders, OBS recording, and rate limiting."
)

CORE_API_URL = os.getenv("CORE_API_URL", f"http://{'127.0.0.1' if config.host in ('0.0.0.0', '') else config.host}:{config.port}")


def _call_api_sync(method: str, endpoint: str, data: Optional[Dict[str, Any]] = None) -> Optional[Any]:
    url = f"{CORE_API_URL}{endpoint}"
    headers = {
        "Content-Type": "application/json",
        "X-Role": "ai_agent",
        "X-Auth-Token": config.secret_token
    }
    payload = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=0.3) as resp:
            content = resp.read().decode("utf-8")
            return json.loads(content) if content else {"success": True}
    except Exception:
        return None


async def _call_api(method: str, endpoint: str, data: Optional[Dict[str, Any]] = None) -> Optional[Any]:
    return await asyncio.to_thread(_call_api_sync, method, endpoint, data)


@mcp.tool()
async def get_camera_status() -> str:
    """Returns current real-time telemetry of the camera (coordinates X/Y/Z, pitch, yaw, roll, FOV, mode, recording status, shaders, client connection, OBS connection)."""
    api_res = await _call_api("GET", "/api/status")
    if api_res is not None:
        return json.dumps(api_res, indent=2, ensure_ascii=False)
    status = camera_controller.get_status()
    return status.model_dump_json(indent=2)


@mcp.tool()
async def move_camera(
    x: float,
    y: float,
    z: float,
    pitch: Optional[float] = None,
    yaw: Optional[float] = None,
    roll: Optional[float] = 0.0,
    duration: float = 1.0,
    smoothing: str = "cinematic"
) -> str:
    """Move the camera smoothly to target 3D coordinates (X, Y, Z). Clamped to safe world borders. Rate limited."""
    body = {
        "x": x, "y": y, "z": z,
        "pitch": pitch, "yaw": yaw, "roll": roll,
        "duration": duration, "smoothing": smoothing
    }
    api_res = await _call_api("POST", "/api/move", body)
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.move_camera(
        x=x, y=y, z=z,
        pitch=pitch, yaw=yaw, roll=roll,
        duration=duration, smoothing=smoothing,
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def orbit_point(
    center_x: float,
    center_y: float,
    center_z: float,
    radius: float = 15.0,
    speed_deg_sec: float = 15.0,
    height_offset: float = 5.0
) -> str:
    """Start an orbit cinematic path around a center point (e.g. around a player, building, or statue)."""
    body = {
        "center_x": center_x, "center_y": center_y, "center_z": center_z,
        "radius": radius, "speed": speed_deg_sec, "height_offset": height_offset
    }
    api_res = await _call_api("POST", "/api/orbit", body)
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.start_orbit(
        center_x=center_x, center_y=center_y, center_z=center_z,
        radius=radius, speed_deg_sec=speed_deg_sec, height_offset=height_offset,
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def set_fov(fov: float, duration: float = 0.5) -> str:
    """Adjust camera zoom / Field of View (FOV between 15.0 and 120.0 degrees)."""
    body = {"fov": fov, "duration": duration}
    api_res = await _call_api("POST", "/api/fov", body)
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.set_fov(
        fov=fov, duration=duration,
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def apply_preset(name: str, duration: float = 1.5) -> str:
    """Fly smoothly to a pre-defined camera waypoint (e.g. 'safe_spawn', 'spawn_aerial', 'sunset_vista')."""
    body = {"name": name, "duration": duration}
    api_res = await _call_api("POST", "/api/presets/apply", body)
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.apply_preset(
        name=name, duration=duration,
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def list_presets() -> str:
    """List all available camera waypoints and presets."""
    api_res = await _call_api("GET", "/api/presets")
    if api_res is not None:
        return json.dumps(api_res, indent=2, ensure_ascii=False)
    return json.dumps(config.presets, indent=2, ensure_ascii=False)


@mcp.tool()
async def save_preset(name: str, description: str = "") -> str:
    """Save the current camera position as a new named preset."""
    body = {"name": name, "description": description}
    api_res = await _call_api("POST", "/api/presets/save", body)
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.save_preset(
        name=name, description=description,
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def start_recording() -> str:
    """Start capturing screen or triggering in-game video recording and OBS Studio recording."""
    api_res = await _call_api("POST", "/api/recording/start", {})
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.start_recording(
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def stop_recording() -> str:
    """Stop the ongoing camera recording and save the video sequence in OBS Studio and in-game."""
    api_res = await _call_api("POST", "/api/recording/stop", {})
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.stop_recording(
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def set_shader(shaderpack: str, profile: str = "High") -> str:
    """Switch the active Iris shaderpack (e.g. 'ComplementaryReimagined', 'BSL', 'Solas') and profile."""
    body = {"shaderpack": shaderpack, "profile": profile}
    api_res = await _call_api("POST", "/api/shaders", body)
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.set_shader(
        shaderpack=shaderpack, profile=profile,
        caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def emergency_stop(reason: str = "AI Agent requested emergency stop") -> str:
    """EMERGENCY 'СТОП-КРАН': Abort all movement, freeze input and instantly teleport camera back to safe spawn."""
    body = {"reason": reason}
    api_res = await _call_api("POST", "/api/emergency_stop", body)
    if api_res is not None:
        return json.dumps(api_res, ensure_ascii=False)

    result = await camera_controller.emergency_stop(
        reason=reason, caller_role=UserRole.AI_AGENT, caller_id="mcp_ai"
    )
    return json.dumps(result, ensure_ascii=False)


@mcp.tool()
async def get_audit_logs(limit: int = 15) -> str:
    """Inspect recent command audit log (who executed what, status, coordinates)."""
    api_res = await _call_api("GET", f"/api/logs?limit={limit}")
    if api_res is not None:
        return json.dumps(api_res, indent=2, ensure_ascii=False)

    logs = await audit_logger.get_logs(limit=limit)
    return json.dumps([entry.model_dump() for entry in logs], indent=2, ensure_ascii=False)


if __name__ == "__main__":
    asyncio.run(mcp.run_stdio_async())
