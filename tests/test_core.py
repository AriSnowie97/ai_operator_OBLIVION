import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.models import UserRole
from core.policy_engine import policy_engine
from core.audit_logger import audit_logger
from core.camera_controller import camera_controller
from core.mcp_server import mcp

async def run_tests():
    print("=== STARTING AI CAMERA OPERATOR CORE TESTS ===")

    await audit_logger.init_db()
    entry = await audit_logger.log("admin", "test_runner", "system_check", {"status": "ok"}, "allowed")
    assert entry.id is not None, "Audit log entry should have a DB id"
    logs = await audit_logger.get_logs(limit=5)
    assert len(logs) >= 1, "Should retrieve at least 1 log entry"
    print("[AuditLogger] DB initialized and logging verified.")

    ok, msg, params = await policy_engine.validate_action(
        UserRole.AI_AGENT, "ai_1", "move_camera",
        {"x": 99999.0, "y": 500.0, "z": -99999.0}
    )
    assert ok, f"Should allow action with clamping: {msg}"
    assert params["x"] == 10000.0, f"Expected clamped X=10000, got {params['x']}"
    assert params["y"] == 320.0, f"Expected clamped Y=320, got {params['y']}"
    assert params["z"] == -10000.0, f"Expected clamped Z=-10000, got {params['z']}"
    print("[PolicyEngine] Coordinate clamping verified.")

    ok_bl, msg_bl, _ = await policy_engine.validate_action(
        UserRole.AI_AGENT, "ai_1", "move_camera",
        {"x": 0.0, "y": 0.0, "z": 0.0}
    )
    assert not ok_bl, "AI Agent must be denied entry into blacklisted admin bunker"
    assert "blacklisted zone" in msg_bl.lower(), f"Unexpected message: {msg_bl}"
    print("[PolicyEngine] Blacklist detection verified.")

    policy_engine.trigger_emergency_stop("Test emergency stop")
    assert policy_engine.is_locked()
    ok_lock, msg_lock, _ = await policy_engine.validate_action(
        UserRole.STREAMER, "streamer_1", "move_camera", {"x": 100.0, "y": 100.0, "z": 100.0}
    )
    assert not ok_lock, "Commands must be rejected while emergency lock is active"

    ok_reset, _ = policy_engine.reset_emergency_lock(UserRole.ADMIN)
    assert ok_reset and not policy_engine.is_locked(), "Admin must be able to reset emergency lock"
    print("[PolicyEngine] Emergency stop (СТОП-КРАН) and admin unlock verified.")

    res_move = await camera_controller.move_camera(
        x=150.0, y=85.0, z=-200.0, duration=0.1, caller_role=UserRole.STREAMER, caller_id="test"
    )
    assert res_move["success"], f"Move camera failed: {res_move}"
    status = camera_controller.get_status()
    assert status.position.x == 150.0, f"Expected X=150.0, got {status.position.x}"
    print("[CameraController] Movement and position updating verified.")

    res_preset = await camera_controller.apply_preset("safe_spawn", duration=0.1)
    assert res_preset["success"], f"Preset application failed: {res_preset}"
    print("[CameraController] Presets application verified.")

    tools = await mcp.list_tools()
    tool_names = [t.name for t in tools]
    required_tools = [
        "get_camera_status", "move_camera", "orbit_point", "set_fov",
        "apply_preset", "save_preset", "list_presets", "start_recording",
        "stop_recording", "set_shader", "emergency_stop", "get_audit_logs"
    ]
    for req in required_tools:
        assert req in tool_names, f"Missing MCP tool: {req}"
    print(f"[MCPServer] All {len(required_tools)} required MCP tools registered and validated!")

    print("\nALL CORE & MCP TESTS PASSED SUCCESSFULLY!")

if __name__ == "__main__":
    asyncio.run(run_tests())
