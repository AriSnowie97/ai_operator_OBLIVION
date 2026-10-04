import sys
import asyncio
import base64
import hashlib
import json
import time
from typing import Optional, Dict, Any, Callable
import websockets

from core.config import config


class OBSClient:
    """
    OBS Studio WebSocket v5 Client.
    Supports auto-reconnect, authentication (salt + challenge SHA256/Base64),
    and control over recording (StartRecord, StopRecord, ToggleRecord, GetRecordStatus).
    """

    def __init__(self):
        self.enabled: bool = config.obs_config.get("enabled", True)
        self.url: str = config.obs_config.get("websocket_url", "ws://localhost:4455")
        self.password: str = config.obs_config.get("password", "")
        self.ws: Optional[Any] = None
        self.is_connected: bool = False
        self.is_recording: bool = False
        self.recording_time_sec: float = 0.0
        self._record_start_time: Optional[float] = None
        
        self._running: bool = True
        self._reconnect_task: Optional[asyncio.Task] = None
        self.on_record_state_change: Optional[Callable[[bool], Any]] = None

    def start_background_loop(self):
        """Start background reconnection and event loop."""
        if self._reconnect_task is None or self._reconnect_task.done():
            self._reconnect_task = asyncio.create_task(self._connection_loop())

    async def _connection_loop(self):
        while self._running:
            if not self.enabled:
                await asyncio.sleep(5)
                continue

            try:
                async with websockets.connect(self.url, open_timeout=3, ping_interval=20) as ws:
                    self.ws = ws
                    # Step 1: Wait for Hello (op: 0)
                    raw_hello = await asyncio.wait_for(ws.recv(), timeout=5.0)
                    hello = json.loads(raw_hello)
                    if hello.get("op") != 0:
                        raise RuntimeError(f"Unexpected initial op: {hello.get('op')}")

                    d = hello.get("d", {})
                    auth_info = d.get("authentication")
                    
                    identify_d: Dict[str, Any] = {
                        "rpcVersion": 1,
                        "eventSubscriptions": 33  # General (1) + Outputs (32 for RecordStateChanged)
                    }

                    if auth_info:
                        if not self.password:
                            print("[OBS] OBS WebSocket requires password, but none configured in config.yaml.", file=sys.stderr)
                        else:
                            salt = auth_info.get("salt", "")
                            challenge = auth_info.get("challenge", "")
                            # Hash 1: base64(sha256(password + salt))
                            s1 = base64.b64encode(hashlib.sha256((self.password + salt).encode("utf-8")).digest()).decode("utf-8")
                            # Hash 2: base64(sha256(s1 + challenge))
                            auth_resp = base64.b64encode(hashlib.sha256((s1 + challenge).encode("utf-8")).digest()).decode("utf-8")
                            identify_d["authentication"] = auth_resp

                    # Step 2: Send Identify (op: 1)
                    await ws.send(json.dumps({"op": 1, "d": identify_d}))

                    # Step 3: Wait for Identified (op: 2)
                    raw_ident = await asyncio.wait_for(ws.recv(), timeout=5.0)
                    ident = json.loads(raw_ident)
                    if ident.get("op") != 2:
                        raise RuntimeError(f"Authentication failed: {ident}")

                    self.is_connected = True
                    print(f"[OBS] Connected and authenticated to OBS Studio ({self.url})!", file=sys.stderr)
                    # Check current record status
                    await ws.send(json.dumps({
                        "op": 6,
                        "d": {
                            "requestType": "GetRecordStatus",
                            "requestId": "obs_init_rec_status"
                        }
                    }))

                    # Listen for events & responses
                    async for msg in ws:
                        data = json.loads(msg)
                        op = data.get("op")
                        
                        # Op 5: Event
                        if op == 5:
                            evt = data.get("d", {})
                            if evt.get("eventType") == "RecordStateChanged":
                                evt_data = evt.get("eventData", {})
                                active = evt_data.get("outputActive", False)
                                self._update_record_state(active)

                        # Op 7: RequestResponse
                        elif op == 7:
                            resp = data.get("d", {})
                            req_id = resp.get("requestId")
                            if req_id == "obs_init_rec_status":
                                resp_data = resp.get("responseData", {})
                                active = resp_data.get("outputActive", False)
                                self._update_record_state(active)

            except (websockets.exceptions.ConnectionClosed, ConnectionRefusedError, OSError, asyncio.TimeoutError):
                self.is_connected = False
                self.ws = None
                await asyncio.sleep(3)
            except Exception as e:
                self.is_connected = False
                self.ws = None
                await asyncio.sleep(3)

    def _update_record_state(self, active: bool):
        self.is_recording = active
        if active and not self._record_start_time:
            self._record_start_time = time.time()
        elif not active:
            self._record_start_time = None
            self.recording_time_sec = 0.0

        if self.on_record_state_change:
            try:
                res = self.on_record_state_change(active)
                if asyncio.iscoroutine(res):
                    asyncio.create_task(res)
            except Exception as err:
                print(f"[OBS] Error in record state callback: {err}", file=sys.stderr)
    async def start_recording(self) -> Dict[str, Any]:
        """Send StartRecord request to OBS."""
        if not self.enabled:
            return {"success": False, "message": "OBS integration disabled in config.yaml"}
        if not self.is_connected or not self.ws:
            return {"success": False, "message": "OBS not connected (ensure OBS Studio is running on port 4455)"}
        try:
            req = {
                "op": 6,
                "d": {
                    "requestType": "StartRecord",
                    "requestId": f"rec_start_{int(time.time()*1000)}"
                }
            }
            await self.ws.send(json.dumps(req))
            self.is_recording = True
            self._record_start_time = time.time()
            return {"success": True, "message": "OBS recording started"}
        except Exception as e:
            return {"success": False, "message": f"Failed to send StartRecord: {e}"}

    async def stop_recording(self) -> Dict[str, Any]:
        """Send StopRecord request to OBS."""
        if not self.enabled:
            return {"success": False, "message": "OBS integration disabled in config.yaml"}
        if not self.is_connected or not self.ws:
            return {"success": False, "message": "OBS not connected"}
        try:
            req = {
                "op": 6,
                "d": {
                    "requestType": "StopRecord",
                    "requestId": f"rec_stop_{int(time.time()*1000)}"
                }
            }
            await self.ws.send(json.dumps(req))
            self.is_recording = False
            self._record_start_time = None
            return {"success": True, "message": "OBS recording stopped"}
        except Exception as e:
            return {"success": False, "message": f"Failed to send StopRecord: {e}"}

    async def get_status(self) -> Dict[str, Any]:
        """Returns current OBS client status."""
        duration = 0.0
        if self.is_recording and self._record_start_time:
            duration = round(time.time() - self._record_start_time, 1)
        return {
            "enabled": self.enabled,
            "connected": self.is_connected,
            "is_recording": self.is_recording,
            "duration_sec": duration,
            "url": self.url
        }

    def close(self):
        self._running = False
        if self._reconnect_task and not self._reconnect_task.done():
            self._reconnect_task.cancel()


obs_client = OBSClient()
