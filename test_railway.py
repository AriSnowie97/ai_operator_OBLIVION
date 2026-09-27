import asyncio
import ssl
import websockets
import json

async def test():
    ssl_ctx = ssl.create_default_context()
    url = "wss://aioperatoroblivion-production.up.railway.app/ws/client"
    print(f"Connecting to: {url}")
    try:
        async with websockets.connect(url, ssl=ssl_ctx, open_timeout=10) as ws:
            print("CONNECTED to Railway! Sending telemetry...")
            payload = {
                "type": "TELEMETRY",
                "x": 0.0, "y": 100.0, "z": 0.0,
                "pitch": 0.0, "yaw": 0.0, "roll": 0.0,
                "fov": 70.0, "fps": 60.0, "ping": 10.0,
                "is_recording": False, "shader": "none"
            }
            await ws.send(json.dumps(payload))
            print("Telemetry sent! Waiting for commands...")
            msg = await asyncio.wait_for(ws.recv(), timeout=5.0)
            print(f"Received from Core: {msg}")
    except asyncio.TimeoutError:
        print("Connected OK — no commands received (server waiting for more clients), все ОК!")
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}")

asyncio.run(test())
