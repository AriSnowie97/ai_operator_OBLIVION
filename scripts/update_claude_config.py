import json
from pathlib import Path

config_paths = [
    Path(r"C:\Users\arina\AppData\Local\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\Claude\claude_desktop_config.json"),
    Path(r"C:\Users\arina\AppData\Roaming\Claude\claude_desktop_config.json")
]

for p in config_paths:
    if p.exists():
        try:
            with open(p, "r", encoding="utf-8") as f:
                data = json.load(f)
            
            if "mcpServers" in data and "minecraft-camera" in data["mcpServers"]:
                data["mcpServers"]["minecraft-camera"]["env"] = {
                    "CORE_API_URL": "https://aioperatoroblivion-production.up.railway.app"
                }
                with open(p, "w", encoding="utf-8") as f:
                    json.dump(data, f, indent=2, ensure_ascii=False)
                print(f"[OK] Updated {p}")
        except Exception as e:
            print(f"[ERR] Failed {p}: {e}")
