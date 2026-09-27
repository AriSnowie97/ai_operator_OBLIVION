import asyncio
import json
from typing import Optional
from core.config import config

class OBSClient:
    def __init__(self):
        self.enabled = config.obs_config.get("enabled", False)
        self.url = config.obs_config.get("websocket_url", "ws://localhost:4455")
        self.ws = None

    async def connect(self):
        if not self.enabled:
            return
        try:
            import websockets
            self.ws = await websockets.connect(self.url)
            print(f"[OBS] Connected to OBS WebSocket at {self.url}")
        except Exception as e:
            print(f"[OBS] Could not connect to OBS WebSocket ({e}). Recording fallback active.")

    async def start_recording(self):
        if not self.enabled or not self.ws:
            return False
        try:
            req = {"op": 6, "d": {"requestType": "StartRecord", "requestId": "cam_rec_start"}}
            await self.ws.send(json.dumps(req))
            return True
        except Exception:
            return False

    async def stop_recording(self):
        if not self.enabled or not self.ws:
            return False
        try:
            req = {"op": 6, "d": {"requestType": "StopRecord", "requestId": "cam_rec_stop"}}
            await self.ws.send(json.dumps(req))
            return True
        except Exception:
            return False

obs_client = OBSClient()
