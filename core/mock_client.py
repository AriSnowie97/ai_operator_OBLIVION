import asyncio
import json
import math
import random
import sys
import websockets

class MockMinecraftClient:
    def __init__(self, uri: str = "ws://localhost:8000/ws/client"):
        self.uri = uri
        self.x = 0.0
        self.y = 100.0
        self.z = 0.0
        self.pitch = 15.0
        self.yaw = 0.0
        self.roll = 0.0
        self.fov = 70.0
        self.target_x = 0.0
        self.target_y = 100.0
        self.target_z = 0.0
        self.target_pitch = 15.0
        self.target_yaw = 0.0
        self.target_roll = 0.0
        self.target_fov = 70.0
        self.is_recording = False
        self.shader = "ComplementaryReimagined"
        self.running = True

    async def connect_and_run(self):
        print(f"[MockClient] Connecting to Core at {self.uri}...")
        while self.running:
            try:
                async with websockets.connect(self.uri) as ws:
                    print("[MockClient] CONNECTED! Simulating GPU Client (Fabric 1.21.1 + Iris Shaders)...")
                    rx_task = asyncio.create_task(self.receive_loop(ws))
                    tx_task = asyncio.create_task(self.telemetry_loop(ws))
                    done, pending = await asyncio.wait([rx_task, tx_task], return_when=asyncio.FIRST_COMPLETED)
                    for task in pending:
                        task.cancel()
            except Exception as e:
                print(f"[MockClient] Connection lost/failed ({e}). Reconnecting in 3 seconds...")
                await asyncio.sleep(3.0)

    async def receive_loop(self, ws):
        while self.running:
            msg_raw = await ws.recv()
            try:
                pkt = json.loads(msg_raw)
                p_type = pkt.get("type")
                if p_type == "MOVE":
                    self.target_x = pkt.get("x", self.target_x)
                    self.target_y = pkt.get("y", self.target_y)
                    self.target_z = pkt.get("z", self.target_z)
                    self.target_pitch = pkt.get("pitch", self.target_pitch)
                    self.target_yaw = pkt.get("yaw", self.target_yaw)
                    self.target_roll = pkt.get("roll", self.target_roll)
                    print(f"[MockClient] Received MOVE to ({self.target_x:.1f}, {self.target_y:.1f}, {self.target_z:.1f})")
                elif p_type == "SET_FOV":
                    self.target_fov = pkt.get("fov", 70.0)
                elif p_type == "SET_SHADER":
                    self.shader = pkt.get("shaderpack", self.shader)
                    print(f"[MockClient] Switched shader to: {self.shader}")
                elif p_type == "START_RECORDING":
                    self.is_recording = True
                    print("[MockClient] Started recording viewport")
                elif p_type == "STOP_RECORDING":
                    self.is_recording = False
                    print("[MockClient] Stopped recording viewport")
                elif p_type == "EMERGENCY_STOP":
                    safe = pkt.get("safe_position", {})
                    self.x = self.target_x = safe.get("x", 0.0)
                    self.y = self.target_y = safe.get("y", 100.0)
                    self.z = self.target_z = safe.get("z", 0.0)
                    self.pitch = self.target_pitch = safe.get("pitch", 0.0)
                    self.yaw = self.target_yaw = safe.get("yaw", 0.0)
                    print("[MockClient] EMERGENCY STOP! Snapped to safe spawn.")
            except Exception as e:
                print(f"[MockClient] Error processing packet: {e}")

    async def telemetry_loop(self, ws):
        while self.running:
            lerp_speed = 0.15
            self.x += (self.target_x - self.x) * lerp_speed
            self.y += (self.target_y - self.y) * lerp_speed
            self.z += (self.target_z - self.z) * lerp_speed
            self.pitch += (self.target_pitch - self.pitch) * lerp_speed
            self.yaw += (self.target_yaw - self.yaw) * lerp_speed
            self.roll += (self.target_roll - self.roll) * lerp_speed
            self.fov += (self.target_fov - self.fov) * lerp_speed

            telemetry = {
                "type": "TELEMETRY",
                "x": round(self.x, 2),
                "y": round(self.y, 2),
                "z": round(self.z, 2),
                "pitch": round(self.pitch, 2),
                "yaw": round(self.yaw, 2),
                "roll": round(self.roll, 2),
                "fov": round(self.fov, 1),
                "fps": round(110.0 + random.uniform(-5.0, 5.0), 1),
                "ping": round(12.0 + random.uniform(-1.0, 2.0), 1),
                "is_recording": self.is_recording,
                "shader": self.shader
            }
            await ws.send(json.dumps(telemetry))
            await asyncio.sleep(0.05)

if __name__ == "__main__":
    client = MockMinecraftClient()
    try:
        asyncio.run(client.connect_and_run())
    except KeyboardInterrupt:
        print("[MockClient] Stopped.")
