import uvicorn
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

from core.config import config

if __name__ == "__main__":
    print(f"Starting AI Camera Operator Core on http://{config.host}:{config.port}")
    uvicorn.run("core.server:app", host=config.host, port=config.port, reload=False)
