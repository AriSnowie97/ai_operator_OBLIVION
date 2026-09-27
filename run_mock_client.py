import asyncio
import sys

sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

from core.mock_client import MockMinecraftClient

if __name__ == "__main__":
    client = MockMinecraftClient()
    try:
        asyncio.run(client.connect_and_run())
    except KeyboardInterrupt:
        print("\n[MockClient] Terminated.")
