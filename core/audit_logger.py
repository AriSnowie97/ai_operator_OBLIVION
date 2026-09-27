import json
import aiosqlite
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import List, Dict, Any, Optional
from core.models import AuditLogEntry

DB_DIR = Path(__file__).resolve().parent.parent / "data"
DB_DIR.mkdir(exist_ok=True)
DB_PATH = DB_DIR / "audit.db"

class AuditLogger:
    def __init__(self, db_path: Path = DB_PATH):
        self.db_path = str(db_path)

    async def init_db(self):
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT NOT NULL,
                    caller_role TEXT NOT NULL,
                    caller_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    parameters TEXT,
                    status TEXT NOT NULL,
                    reason TEXT
                )
            """)
            await db.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_logs(timestamp)")
            await db.execute("CREATE INDEX IF NOT EXISTS idx_audit_role ON audit_logs(caller_role)")
            await db.commit()

    async def log(
        self,
        caller_role: str,
        caller_id: str,
        action: str,
        parameters: Optional[Dict[str, Any]] = None,
        status: str = "allowed",
        reason: Optional[str] = None
    ) -> AuditLogEntry:
        now_iso = datetime.now(timezone.utc).isoformat()
        params_json = json.dumps(parameters or {}, ensure_ascii=False)
        
        async with aiosqlite.connect(self.db_path) as db:
            cursor = await db.execute(
                """
                INSERT INTO audit_logs (timestamp, caller_role, caller_id, action, parameters, status, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (now_iso, caller_role, caller_id, action, params_json, status, reason)
            )
            entry_id = cursor.lastrowid
            await db.commit()
            
        return AuditLogEntry(
            id=entry_id,
            timestamp=now_iso,
            caller_role=caller_role,
            caller_id=caller_id,
            action=action,
            parameters=parameters or {},
            status=status,
            reason=reason
        )

    async def get_logs(self, limit: int = 50, offset: int = 0) -> List[AuditLogEntry]:
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            cursor = await db.execute(
                """
                SELECT id, timestamp, caller_role, caller_id, action, parameters, status, reason
                FROM audit_logs
                ORDER BY id DESC
                LIMIT ? OFFSET ?
                """,
                (limit, offset)
            )
            rows = await cursor.fetchall()
            entries = []
            for row in rows:
                try:
                    params = json.loads(row["parameters"]) if row["parameters"] else {}
                except Exception:
                    params = {}
                entries.append(AuditLogEntry(
                    id=row["id"],
                    timestamp=row["timestamp"],
                    caller_role=row["caller_role"],
                    caller_id=row["caller_id"],
                    action=row["action"],
                    parameters=params,
                    status=row["status"],
                    reason=row["reason"]
                ))
            return entries

    async def prune_old_logs(self, days_retention: int = 7):
        cutoff = (datetime.now(timezone.utc) - timedelta(days=days_retention)).isoformat()
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute("DELETE FROM audit_logs WHERE timestamp < ?", (cutoff,))
            await db.commit()

audit_logger = AuditLogger()
