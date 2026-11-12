"""Persistent long-running task metadata and the price-change monitoring task."""

import json
import sqlite3
import time
from contextlib import contextmanager
from decimal import Decimal, InvalidOperation
from pathlib import Path


DISCOVERY_TASK_ID = "price_candidate_discovery"
SNAPSHOT_TASK_ID = "price_snapshot"
TASK_NAME = "Поиск товаров с изменением цен"
DEFAULT_INTERVAL_SECONDS = 3600
CANDIDATE_TTL_SECONDS = 12 * 60 * 60
PRODUCT_BATCH_SIZE = 100


def _canonical_prices(product):
    result = {}
    for map_name in ("pricesMap", "prices99Map"):
        price_map = product.get(map_name) or {}
        normalized = {}
        if isinstance(price_map, dict):
            for key, value in price_map.items():
                if not isinstance(value, dict):
                    continue
                number = value.get("number", key)
                raw_price = str(value.get("value", ""))
                try:
                    raw_price = format(Decimal(raw_price).normalize(), "f")
                except (InvalidOperation, ValueError):
                    pass
                normalized[str(number)] = raw_price
        result[map_name] = dict(sorted(normalized.items()))
    return result


class TaskStore:
    def __init__(self, path):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    @contextmanager
    def _connect(self):
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout = 15000")
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def _initialize(self):
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS long_tasks (
                    task_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    interval_seconds INTEGER NOT NULL,
                    next_run_at REAL,
                    last_started_at REAL,
                    last_finished_at REAL,
                    last_status TEXT NOT NULL DEFAULT 'Ожидает запуска',
                    last_result TEXT NOT NULL DEFAULT ''
                );
                CREATE TABLE IF NOT EXISTS price_candidates (
                    marking TEXT PRIMARY KEY,
                    first_seen_at REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    source TEXT NOT NULL DEFAULT 'NearlyPriceChanging'
                );
                CREATE TABLE IF NOT EXISTS price_snapshots (
                    marking TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    barcode TEXT NOT NULL DEFAULT '',
                    prices_json TEXT NOT NULL,
                    captured_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS price_changes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    marking TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    barcode TEXT NOT NULL DEFAULT '',
                    detected_at REAL NOT NULL,
                    old_prices_json TEXT NOT NULL,
                    new_prices_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_price_changes_detected
                    ON price_changes(detected_at DESC);
                """
            )
            connection.execute(
                """UPDATE long_tasks SET task_id=?, name=?
                   WHERE task_id='price_change_monitor'
                   AND NOT EXISTS (
                       SELECT 1 FROM long_tasks WHERE task_id=?
                   )""",
                (DISCOVERY_TASK_ID, TASK_NAME, DISCOVERY_TASK_ID),
            )
            connection.executemany(
                """INSERT OR IGNORE INTO long_tasks
                   (task_id, name, enabled, interval_seconds, last_status)
                   VALUES (?, ?, 1, ?, 'Ожидает запуска')""",
                [
                    (DISCOVERY_TASK_ID, TASK_NAME, DEFAULT_INTERVAL_SECONDS),
                    (
                        SNAPSHOT_TASK_ID,
                        "Снимки и сравнение цен",
                        DEFAULT_INTERVAL_SECONDS,
                    ),
                ],
            )
            connection.execute(
                """UPDATE long_tasks SET enabled=0, next_run_at=NULL,
                   last_status='Отключена (устаревшая задача)'
                   WHERE task_id='price_change_monitor'"""
            )
            connection.execute(
                """UPDATE long_tasks SET last_status='Прервано при закрытии приложения',
                   last_result='Предыдущий запуск не завершился.'
                   WHERE last_status='Выполняется'"""
            )

    def list_tasks(self):
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM long_tasks ORDER BY name"
            ).fetchall()
        return [dict(row) for row in rows]

    def configure(self, task_id, enabled, interval_seconds):
        interval_seconds = max(60, min(int(interval_seconds), 7 * 24 * 3600))
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT last_started_at FROM long_tasks WHERE task_id=?", (task_id,)
            ).fetchone()
            next_run = time.time() + interval_seconds
            if row and row["last_started_at"] is not None:
                next_run = max(next_run, row["last_started_at"] + interval_seconds)
            connection.execute(
                """UPDATE long_tasks SET enabled=?, interval_seconds=?,
                   next_run_at=CASE WHEN ? THEN ? ELSE NULL END
                   WHERE task_id=?""",
                (
                    int(enabled),
                    interval_seconds,
                    int(enabled),
                    next_run,
                    task_id,
                ),
            )

    def claim_due(self, now=None):
        now = time.time() if now is None else now
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """SELECT * FROM long_tasks WHERE enabled=1
                   AND (next_run_at IS NULL OR next_run_at<=?)
                   ORDER BY next_run_at, task_id LIMIT 1""",
                (now,),
            ).fetchone()
            if row is None:
                return None
            connection.execute(
                "UPDATE long_tasks SET next_run_at=?, last_started_at=?, last_status='Выполняется', last_result='' WHERE task_id=?",
                (now + row["interval_seconds"], now, row["task_id"]),
            )
            return dict(row)

    def force_claim(self, task_id, now=None):
        now = time.time() if now is None else now
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT * FROM long_tasks WHERE task_id=?", (task_id,)
            ).fetchone()
            if row is None:
                return None
            connection.execute(
                "UPDATE long_tasks SET next_run_at=?, last_started_at=?, last_status='Выполняется', last_result='' WHERE task_id=?",
                (now + row["interval_seconds"], now, task_id),
            )
            return dict(row)

    def finish(self, task_id, status, result, finished_at=None):
        finished_at = time.time() if finished_at is None else finished_at
        with self._connect() as connection:
            connection.execute(
                "UPDATE long_tasks SET last_finished_at=?, last_status=?, last_result=? WHERE task_id=?",
                (finished_at, status, str(result), task_id),
            )

    def record_candidates(self, markings, now=None):
        now = time.time() if now is None else now
        markings = list(
            dict.fromkeys(
                str(marking).strip()
                for marking in markings
                if marking is not None and str(marking).strip()
            )
        )
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            for marking in markings:
                was_active = connection.execute(
                    "SELECT 1 FROM price_candidates WHERE marking=? AND expires_at>?",
                    (marking, now),
                ).fetchone()
                if was_active is None:
                    connection.execute(
                        "DELETE FROM price_snapshots WHERE marking=?", (marking,)
                    )
            expired = [
                row["marking"]
                for row in connection.execute(
                    "SELECT marking FROM price_candidates WHERE expires_at<=?", (now,)
                ).fetchall()
            ]
            if expired:
                connection.executemany(
                    "DELETE FROM price_snapshots WHERE marking=?",
                    ((marking,) for marking in expired),
                )
                connection.executemany(
                    "DELETE FROM price_candidates WHERE marking=?",
                    ((marking,) for marking in expired),
                )
            for marking in markings:
                connection.execute(
                    """INSERT OR IGNORE INTO price_candidates
                       (marking, first_seen_at, expires_at) VALUES (?, ?, ?)""",
                    (marking, now, now + CANDIDATE_TTL_SECONDS),
                )
        return len(markings)

    def active_candidates(self, now=None):
        now = time.time() if now is None else now
        with self._connect() as connection:
            return [
                row["marking"]
                for row in connection.execute(
                    "SELECT marking FROM price_candidates WHERE expires_at>? ORDER BY first_seen_at",
                    (now,),
                ).fetchall()
            ]

    def record_snapshots(self, products, active_markings, now=None):
        now = time.time() if now is None else now
        active_markings = set(active_markings)
        changes = 0
        snapshots_updated = 0
        unmatched = 0
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            for product in products:
                if not isinstance(product, dict):
                    continue
                marking = str(product.get("marking") or "").strip()
                if not marking:
                    continue
                if marking not in active_markings:
                    unmatched += 1
                    continue
                prices = _canonical_prices(product)
                prices_json = json.dumps(prices, ensure_ascii=False, sort_keys=True)
                previous = connection.execute(
                    "SELECT prices_json FROM price_snapshots WHERE marking=?",
                    (marking,),
                ).fetchone()
                name = str(product.get("fullName") or product.get("name") or "")
                barcode = str(product.get("barCode") or product.get("barcode") or "")
                if previous is not None and previous["prices_json"] != prices_json:
                    connection.execute(
                        """INSERT INTO price_changes
                           (marking, name, barcode, detected_at, old_prices_json, new_prices_json)
                           VALUES (?, ?, ?, ?, ?, ?)""",
                        (
                            marking,
                            name,
                            barcode,
                            now,
                            previous["prices_json"],
                            prices_json,
                        ),
                    )
                    changes += 1
                connection.execute(
                    """INSERT INTO price_snapshots
                       (marking, name, barcode, prices_json, captured_at) VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT(marking) DO UPDATE SET name=excluded.name,
                       barcode=excluded.barcode, prices_json=excluded.prices_json,
                       captured_at=excluded.captured_at""",
                    (marking, name, barcode, prices_json, now),
                )
                snapshots_updated += 1
        return {
            "snapshots": snapshots_updated,
            "changes": changes,
            "unmatched": unmatched,
        }

    def recent_changes(self, limit=200, task_id=None):
        with self._connect() as connection:
            if task_id not in (None, SNAPSHOT_TASK_ID):
                return []
            rows = connection.execute(
                "SELECT * FROM price_changes ORDER BY detected_at DESC LIMIT ?",
                (max(1, min(int(limit), 1000)),),
            ).fetchall()
        return [dict(row) for row in rows]


def run_candidate_discovery(store, client, now=None):
    now = time.time() if now is None else now
    markings = client.get_nearly_price_changing(shelf=12)
    count = store.record_candidates(markings, now=now)
    return (
        f"Получено артикулов: {count}; активных сейчас: "
        f"{len(store.active_candidates(now=now))}; срок новых — 12 часов"
    )


def run_price_snapshots(store, client, now=None):
    now = time.time() if now is None else now
    active = store.active_candidates(now=now)
    products = []
    for start in range(0, len(active), PRODUCT_BATCH_SIZE):
        products.extend(
            client.get_products(active[start : start + PRODUCT_BATCH_SIZE], shelf=12)
        )
    counts = store.record_snapshots(products, active_markings=active, now=now)
    return (
        f"Активных артикулов: {len(active)}; снимков цен: {counts['snapshots']}; "
        f"новых изменений: {counts['changes']}; "
        f"ответов с неизвестным ProductVO.marking: {counts['unmatched']}"
    )
