import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.mcp_server import mcp, get_camera_status, move_camera, start_recording, stop_recording, set_fov, apply_preset

async def test_mcp_tools():
    print("=== TESTING MCP TOOLS DIRECT INVOCATION ===")
    
    # 1. Test get_camera_status
    status_raw = await get_camera_status()
    status = json.loads(status_raw)
    assert "position" in status or "x" in status.get("position", {}), "Expected position in status"
    print(f"[OK] get_camera_status: recording={status.get('is_recording')}, obs_connected={status.get('obs_connected')}")

    # 2. Test start_recording
    rec_start_raw = await start_recording()
    rec_start = json.loads(rec_start_raw)
    print(f"[OK] start_recording: {rec_start}")
    assert rec_start.get("success") is True

    # Check status is now recording
    status2 = json.loads(await get_camera_status())
    assert status2.get("is_recording") is True, "Status should indicate recording active"
    print("[OK] Verified camera is in recording state")

    # 3. Test stop_recording
    rec_stop_raw = await stop_recording()
    rec_stop = json.loads(rec_stop_raw)
    print(f"[OK] stop_recording: {rec_stop}")
    assert rec_stop.get("success") is True

    # 4. Test move_camera
    move_raw = await move_camera(x=120.0, y=75.0, z=-50.0, duration=0.1)
    move = json.loads(move_raw)
    assert move.get("success") is True
    print(f"[OK] move_camera: {move}")

    # 5. Test set_fov
    fov_raw = await set_fov(fov=45.0, duration=0.1)
    fov_res = json.loads(fov_raw)
    assert fov_res.get("success") is True
    print(f"[OK] set_fov: {fov_res}")

    print("\nALL MCP TESTS PASSED PERFECTLY!")

if __name__ == "__main__":
    asyncio.run(test_mcp_tools())
