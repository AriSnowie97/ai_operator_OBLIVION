import os
import json
import yaml
from pathlib import Path
from typing import Dict, Any, List

BASE_DIR = Path(__file__).resolve().parent.parent
CONFIG_DIR = BASE_DIR / "configs"

class Config:
    def __init__(self):
        self.config_path = CONFIG_DIR / "config.yaml"
        self.blacklist_path = CONFIG_DIR / "blacklist_zones.json"
        self.presets_path = CONFIG_DIR / "presets.json"
        
        self.raw_config: Dict[str, Any] = self._load_yaml(self.config_path)
        self.blacklist_zones: List[Dict[str, Any]] = self._load_json(self.blacklist_path, [])
        self.presets: Dict[str, Any] = self._load_json(self.presets_path, {})
        
        server_cfg = self.raw_config.get("server", {})
        self.host: str = os.getenv("HOST", server_cfg.get("host", "0.0.0.0"))
        self.port: int = int(os.getenv("PORT", server_cfg.get("port", 8000)))
        self.secret_token: str = os.getenv("SECRET_TOKEN", server_cfg.get("secret_token", "obl-operator-secret-2026"))
        
        self.roles_config = self.raw_config.get("roles", {})
        self.safety_config = self.raw_config.get("safety", {})
        self.obs_config = self.raw_config.get("obs", {})
        
    def _load_yaml(self, path: Path) -> Dict[str, Any]:
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                return yaml.safe_load(f) or {}
        return {}

    def _load_json(self, path: Path, default: Any) -> Any:
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        return default

    def save_presets(self, presets_data: Dict[str, Any]):
        self.presets = presets_data
        with open(self.presets_path, "w", encoding="utf-8") as f:
            json.dump(presets_data, f, indent=2, ensure_ascii=False)

    def save_blacklist(self, blacklist_data: List[Dict[str, Any]]):
        self.blacklist_zones = blacklist_data
        with open(self.blacklist_path, "w", encoding="utf-8") as f:
            json.dump(blacklist_data, f, indent=2, ensure_ascii=False)

config = Config()
