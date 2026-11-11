import json
import sqlite3
from datetime import datetime

from config import PRINT_HISTORY_PATH


class PrintHistory:
    """Persistent local archive of print batches."""

    def __init__(self, path=None):
        self.path = path or PRINT_HISTORY_PATH
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS print_batches (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    status TEXT NOT NULL,
                    entries_json TEXT NOT NULL,
                    successful_indexes_json TEXT NOT NULL DEFAULT '[]'
                )
                """
            )

    def _connect(self):
        return sqlite3.connect(str(self.path))

    def add_batch(self, entries):
        created_at = datetime.now().astimezone().isoformat(timespec="seconds")
        with self._connect() as connection:
            cursor = connection.execute(
                "INSERT INTO print_batches (created_at, status, entries_json) VALUES (?, ?, ?)",
                (created_at, "Отправляется", json.dumps(entries, ensure_ascii=False)),
            )
            return cursor.lastrowid

    def finish_batch(self, batch_id, successful_indexes, status):
        with self._connect() as connection:
            connection.execute(
                "UPDATE print_batches SET status = ?, successful_indexes_json = ? WHERE id = ?",
                (status, json.dumps(successful_indexes), batch_id),
            )

    def fail_batch(self, batch_id):
        if batch_id is None:
            return
        with self._connect() as connection:
            connection.execute(
                "UPDATE print_batches SET status = ? WHERE id = ?",
                ("Ошибка отправки", batch_id),
            )

    def batches(self):
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT id, created_at, status, entries_json, successful_indexes_json "
                "FROM print_batches ORDER BY id DESC"
            ).fetchall()
        return [
            {
                "id": row[0],
                "created_at": row[1],
                "status": row[2],
                "entries": json.loads(row[3]),
                "successful_indexes": json.loads(row[4]),
            }
            for row in rows
        ]
