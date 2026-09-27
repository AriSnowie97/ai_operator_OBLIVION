import time
import math
from typing import Tuple, Optional, Dict, Any
from core.config import config
from core.models import UserRole, CameraPosition
from core.audit_logger import audit_logger

BANNED_WORDS = {"cheat", "nuke", "dox", "crash", "exploit", "swastika"}

class RateLimiter:
    def __init__(self):
        self.callers: Dict[str, Dict[str, Any]] = {}

    def check_limit(self, caller_id: str, max_per_sec: float) -> Tuple[bool, float]:
        if max_per_sec <= 0:
            return False, 999.0
        if max_per_sec >= 100:
            return True, 0.0

        now = time.time()
        state = self.callers.get(caller_id)
        if not state:
            self.callers[caller_id] = {
                "tokens": max_per_sec,
                "last_update": now,
                "capacity": max_per_sec
            }
            state = self.callers[caller_id]

        elapsed = now - state["last_update"]
        state["last_update"] = now
        state["tokens"] = min(state["capacity"], state["tokens"] + elapsed * max_per_sec)

        if state["tokens"] >= 1.0:
            state["tokens"] -= 1.0
            return True, 0.0
        else:
            time_needed = (1.0 - state["tokens"]) / max_per_sec
            return False, time_needed

class PolicyEngine:
    def __init__(self):
        self.rate_limiter = RateLimiter()
        self.emergency_locked: bool = False
        self.emergency_reason: Optional[str] = None
        self.last_jump_times: Dict[str, float] = {}

    def is_locked(self) -> bool:
        return self.emergency_locked

    def trigger_emergency_stop(self, reason: str = "Emergency stop triggered") -> Tuple[bool, str]:
        self.emergency_locked = True
        self.emergency_reason = reason
        return True, f"Emergency lock activated: {reason}"

    def reset_emergency_lock(self, role: UserRole) -> Tuple[bool, str]:
        if role != UserRole.ADMIN:
            return False, "Only Admin can release the emergency lock"
        self.emergency_locked = False
        self.emergency_reason = None
        return True, "Emergency lock released"

    async def validate_action(
        self,
        role: UserRole,
        caller_id: str,
        action: str,
        parameters: Dict[str, Any],
        current_pos: Optional[CameraPosition] = None
    ) -> Tuple[bool, str, Dict[str, Any]]:
        if self.emergency_locked and action not in ("emergency_reset", "get_status", "get_logs", "emergency_stop"):
            msg = f"Operation denied: Emergency stop is active ({self.emergency_reason})"
            await audit_logger.log(role.value, caller_id, action, parameters, status="denied", reason=msg)
            return False, msg, parameters

        if role == UserRole.VIEWER and action not in ("get_status", "get_logs", "list_presets"):
            msg = "Operation denied: Viewers have read-only access"
            await audit_logger.log(role.value, caller_id, action, parameters, status="denied", reason=msg)
            return False, msg, parameters

        role_cfg = config.roles_config.get(role.value, {})
        max_rate = role_cfg.get("rate_limit_per_sec", 1.0)
        allowed, retry_after = self.rate_limiter.check_limit(f"{role.value}:{caller_id}", max_rate)
        if not allowed:
            msg = f"Rate limit exceeded for {role.value}. Retry in {retry_after:.2f}s"
            await audit_logger.log(role.value, caller_id, action, parameters, status="denied", reason=msg)
            return False, msg, parameters

        if action == "set_shader" and not role_cfg.get("allow_shaders", False):
            msg = f"Role '{role.value}' is not allowed to modify shader settings"
            await audit_logger.log(role.value, caller_id, action, parameters, status="denied", reason=msg)
            return False, msg, parameters

        for k in ("name", "description", "caption", "reason"):
            if k in parameters and isinstance(parameters[k], str):
                text_lower = parameters[k].lower()
                if any(bad in text_lower for bad in BANNED_WORDS):
                    msg = f"Prohibited word detected in parameter '{k}'"
                    await audit_logger.log(role.value, caller_id, action, parameters, status="denied", reason=msg)
                    return False, msg, parameters

        modified_params = dict(parameters)
        if "x" in parameters and "y" in parameters and "z" in parameters:
            x, y, z = float(parameters["x"]), float(parameters["y"]), float(parameters["z"])

            if current_pos and role == UserRole.STREAMER:
                dist = math.sqrt((x - current_pos.x)**2 + (y - current_pos.y)**2 + (z - current_pos.z)**2)
                max_jump = config.safety_config.get("max_instant_jump_distance", 250.0)
                duration = float(parameters.get("duration_sec", 1.0))
                if dist > max_jump and duration < 1.0:
                    now = time.time()
                    last_time = self.last_jump_times.get(caller_id, 0.0)
                    if now - last_time < 3.0:
                        msg = f"Large teleport ({dist:.1f}m) on cooldown. Use smoothing duration or wait 3s"
                        await audit_logger.log(role.value, caller_id, action, parameters, status="denied", reason=msg)
                        return False, msg, parameters
                    self.last_jump_times[caller_id] = now

            clamp = config.safety_config.get("world_clamp", {})
            clamped_x = max(clamp.get("min_x", -10000.0), min(clamp.get("max_x", 10000.0), x))
            clamped_y = max(clamp.get("min_y", -64.0), min(clamp.get("max_y", 320.0), y))
            clamped_z = max(clamp.get("min_z", -10000.0), min(clamp.get("max_z", 10000.0), z))
            modified_params["x"], modified_params["y"], modified_params["z"] = clamped_x, clamped_y, clamped_z

            if not role_cfg.get("allow_blacklist_bypass", False):
                for zone in config.blacklist_zones:
                    min_pt, max_pt = zone.get("min", [0, 0, 0]), zone.get("max", [0, 0, 0])
                    if (min_pt[0] <= clamped_x <= max_pt[0] and
                        min_pt[1] <= clamped_y <= max_pt[1] and
                        min_pt[2] <= clamped_z <= max_pt[2]):
                        msg = f"Target coordinates ({clamped_x:.1f}, {clamped_y:.1f}, {clamped_z:.1f}) are inside blacklisted zone '{zone.get('name')}'"
                        await audit_logger.log(role.value, caller_id, action, parameters, status="denied", reason=msg)
                        return False, msg, parameters

        await audit_logger.log(role.value, caller_id, action, modified_params, status="allowed")
        return True, "Action allowed", modified_params

policy_engine = PolicyEngine()
