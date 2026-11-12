from collections import defaultdict
from datetime import datetime
from decimal import Decimal, InvalidOperation
import json
import re

from PySide6.QtCore import QObject, QSize, Qt, QThread, QTimer, Signal, Slot
from PySide6.QtGui import QAction, QPixmap
from PySide6.QtWidgets import (
    QComboBox,
    QCheckBox,
    QFrame,
    QFormLayout,
    QGridLayout,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMdiArea,
    QMdiSubWindow,
    QMenu,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QSpinBox,
    QStatusBar,
    QGroupBox,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

import auth
from auth import get_authenticated_session
import retail
from config import DB_PATH, PRINT_FORMATS, TASKS_DB_PATH
from print_history import PrintHistory
from long_tasks import (
    DISCOVERY_TASK_ID,
    SNAPSHOT_TASK_ID,
    TaskStore,
    run_candidate_discovery,
    run_price_snapshots,
)
from setretail_client import SetRetailXClient


def _format_price(value):
    """Format a catalog price stored in kopecks as rubles and kopecks."""
    if value in (None, ""):
        return "—"
    try:
        rubles = Decimal(str(value)) / 100
        formatted = f"{rubles:,.2f}"
        return formatted.replace(",", "\0").replace(".", ",").replace("\0", " ") + " ₽"
    except (InvalidOperation, TypeError, ValueError):
        return f"{value} ₽"


class TaskThread(QThread):
    succeeded = Signal(object)
    failed = Signal(str)

    def __init__(self, function, parent=None):
        super().__init__(parent)
        self.function = function

    def run(self):
        try:
            self.succeeded.emit(self.function())
        except Exception as error:
            self.failed.emit(str(error))


class PriceComparisonThread(QThread):
    """Look up scanned labels locally, then compare them in one RPC batch."""

    completed = Signal(object)

    def __init__(self, client, scans, parent=None):
        super().__init__(parent)
        self.client = client
        self.scans = scans

    @staticmethod
    def _prices(product, map_name):
        prices = product.get(map_name) or {}
        if not isinstance(prices, dict):
            return {}
        result = {}
        for key, value in prices.items():
            if not isinstance(value, dict):
                continue
            number = str(value.get("number", key))
            if number not in ("1", "2", "4"):
                continue
            try:
                result[number] = Decimal(str(value.get("value")))
            except (InvalidOperation, TypeError, ValueError):
                continue
        return result

    def run(self):
        results = []
        products_by_id = {}
        markings = []
        for scan in self.scans:
            result = {"id": scan["id"], "product": None, "status": "error"}
            try:
                product = retail.search_offline(scan["barcode"])
            except Exception as error:
                result["error"] = str(error)
                results.append(result)
                continue
            if product is None:
                result["error"] = "Штрихкод не найден в локальной базе"
                results.append(result)
                continue
            marking = retail.product_print_code(product)
            result["product"] = product
            result["marking"] = marking
            products_by_id[scan["id"]] = result
            markings.append(marking)
            results.append(result)

        if markings:
            try:
                remote_products = self.client.get_products(
                    list(dict.fromkeys(markings)), shelf=12
                )
                by_marking = {
                    str(product.get("marking")): product
                    for product in remote_products
                    if product.get("marking") is not None
                }
                by_barcode = {
                    str(product.get("barCode") or product.get("barcode")): product
                    for product in remote_products
                    if product.get("barCode") or product.get("barcode")
                }
                scans_by_id = {scan["id"]: scan for scan in self.scans}
                for scan_id, result in products_by_id.items():
                    product = result["product"]
                    remote = by_barcode.get(
                        str(product.get("barcode"))
                    ) or by_marking.get(result["marking"])
                    if remote is None:
                        result["error"] = "Товар не вернулся из SetRetailX"
                        continue
                    normal = self._prices(remote, "pricesMap")
                    promo = self._prices(remote, "prices99Map")
                    scan = scans_by_id[scan_id]
                    comparisons = {}
                    for number in ("1", "2", "4"):
                        scanned_price = scan["prices"].get(number)
                        available = [
                            price
                            for price in (normal.get(number), promo.get(number))
                            if price is not None
                        ]
                        comparisons[number] = (
                            None
                            if scanned_price is None or not available
                            else any(scanned_price == price for price in available)
                        )
                    result.update(
                        status="checked",
                        remote=remote,
                        normal=normal,
                        promo=promo,
                        comparisons=comparisons,
                    )
            except Exception as error:
                for result in products_by_id.values():
                    result["error"] = str(error)
        self.completed.emit(results)


def _parse_price_scan(value):
    """Parse the scanner's prefix plus barcode/price query-string format."""
    text = str(value).strip()
    barcode_match = re.search(r"barcode=(\d+)", text, re.IGNORECASE)
    if not barcode_match:
        raise ValueError("В строке сканера не найден barcode")
    prices = {}
    for number, raw in re.findall(
        r"(?:^|&)(price[124])=([^&\s]+)", text, re.IGNORECASE
    ):
        try:
            price = Decimal(raw.replace(",", "."))
        except (InvalidOperation, ValueError):
            raise ValueError(f"Некорректная цена {number}")
        if not price.is_finite() or price < 0:
            raise ValueError(f"Некорректная цена {number}")
        prices[number[-1]] = price
    if not prices:
        raise ValueError("В строке сканера не найдены price1, price2 или price4")
    return {"barcode": barcode_match.group(1), "prices": prices}


def _scan_price_text(value):
    if value is None:
        return "—"
    return f"{value:.2f}".replace(".", ",")


class PriceComparisonWindow(QWidget):
    """Non-blocking scan queue and comparison with SetRetailX prices."""

    def __init__(self, printer):
        super().__init__()
        self.printer = printer
        self.records = []
        self.next_scan_id = 1
        self.pending = []
        self.worker = None
        self.flush_timer = QTimer(self)
        self.flush_timer.setSingleShot(True)
        self.flush_timer.setInterval(120)
        self.flush_timer.timeout.connect(self.process_pending)

        layout = QVBoxLayout(self)
        input_row = QHBoxLayout()
        input_row.addWidget(QLabel("Сканируйте ценник:"))
        self.scan_input = QLineEdit()
        self.scan_input.setPlaceholderText(
            r"\000026barcode=…&price1=…&price2=…&price4=…"
        )
        self.scan_input.returnPressed.connect(self.add_scan)
        input_row.addWidget(self.scan_input, 1)
        self.add_button = QPushButton("Добавить")
        self.add_button.clicked.connect(self.add_scan)
        input_row.addWidget(self.add_button)
        layout.addLayout(input_row)

        self.table = QTableWidget(0, 16)
        self.table.setHorizontalHeaderLabels(
            [
                "Статус",
                "Артикул",
                "Штрихкод",
                "Ярлык 1",
                "Ярлык 2",
                "Ярлык 4",
                "Обычная 1",
                "Обычная 2",
                "Обычная 4",
                "Акция 1",
                "Акция 2",
                "Акция 4",
                "Сверка 1",
                "Сверка 2",
                "Сверка 4",
                "Результат",
            ]
        )
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setAlternatingRowColors(True)
        self.table.verticalHeader().setVisible(False)
        header = self.table.horizontalHeader()
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        for column in (0, *range(3, 16)):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        layout.addWidget(self.table, 1)

        footer = QHBoxLayout()
        self.status_label = QLabel("Ожидаю сканирование")
        footer.addWidget(self.status_label, 1)
        self.clear_button = QPushButton("Очистить результаты")
        self.clear_button.clicked.connect(self.clear_results)
        footer.addWidget(self.clear_button)
        layout.addLayout(footer)

    def add_scan(self):
        text = self.scan_input.text()
        if not text.strip():
            return
        try:
            scan = _parse_price_scan(text)
        except ValueError as error:
            self.status_label.setText(str(error))
            return
        scan.update(id=self.next_scan_id, status="pending")
        self.next_scan_id += 1
        self.records.append(scan)
        self.pending.append(scan)
        self.scan_input.clear()
        self.refresh_table()
        self.status_label.setText(f"В очереди проверки: {len(self.pending)}")
        self.flush_timer.start()

    def process_pending(self):
        if self.worker is not None and self.worker.isRunning():
            return
        if not self.pending:
            return
        if self.printer.setretail_client is None:
            self.status_label.setText("Ожидаю подключения к SetRetailX")
            return
        scans = self.pending[:50]
        del self.pending[: len(scans)]
        for scan in scans:
            scan["status"] = "checking"
        self.refresh_table()
        self.status_label.setText(f"Проверяем {len(scans)} ценников…")
        self.worker = PriceComparisonThread(self.printer.setretail_client, scans, self)
        self.worker.completed.connect(self.batch_completed)
        self.worker.finished.connect(self.batch_finished)
        self.worker.start()

    def batch_completed(self, results):
        by_id = {record["id"]: record for record in self.records}
        queued = 0
        for result in results:
            record = by_id.get(result["id"])
            if record is None:
                continue
            record.update(result)
            if result.get("status") != "checked":
                record["status"] = "error"
                continue
            record["status"] = "checked"
            mismatched = any(value is False for value in result["comparisons"].values())
            if mismatched and not record.get("queued_for_print"):
                product = result["product"]
                self.printer.entries.append(
                    {
                        "st_code": product["st_code"],
                        "name": product["name"],
                        "vendor_code": product["vendor_code"],
                        "uom": product.get("uom") or "ST",
                        "barcode": product["barcode"],
                        "source": "offline",
                        "format": self.printer.scanner.format_combo.currentData(),
                        "amount": self.printer.scanner.amount_spin.value(),
                        "quantity": 1,
                    }
                )
                record["queued_for_print"] = True
                queued += 1
        if queued:
            self.printer.refresh_queue()
        self.refresh_table()
        self.status_label.setText(
            f"Проверено · в очередь печати добавлено: {queued}"
            if queued
            else "Проверка завершена"
        )

    def batch_finished(self):
        self.worker = None
        if self.pending:
            self.process_pending()

    def refresh_table(self):
        self.table.setRowCount(len(self.records))
        for row, record in enumerate(self.records):
            product = record.get("product") or {}
            normal = record.get("normal") or {}
            promo = record.get("promo") or {}
            comparison = record.get("comparisons") or {}
            state = {
                "pending": "В очереди",
                "checking": "Проверка…",
                "checked": "Готово",
                "error": record.get("error", "Ошибка"),
            }.get(record.get("status"), "—")
            outcome = (
                "В очередь печати"
                if record.get("queued_for_print")
                else ("Совпадает" if record.get("status") == "checked" else "—")
            )
            values = [
                state,
                product.get("vendor_code") or "—",
                record["barcode"],
                *[
                    _scan_price_text(record["prices"].get(number))
                    for number in ("1", "2", "4")
                ],
                *[_scan_price_text(normal.get(number)) for number in ("1", "2", "4")],
                *[_scan_price_text(promo.get(number)) for number in ("1", "2", "4")],
                *[
                    {True: "✓", False: "✗", None: "—"}.get(comparison.get(number), "—")
                    for number in ("1", "2", "4")
                ],
                outcome,
            ]
            for column, value in enumerate(values):
                item = QTableWidgetItem(str(value))
                if column in (0, *range(3, 16)):
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                if column in (12, 13, 14):
                    marker = comparison.get(str(column - 11))
                    if marker is True:
                        item.setForeground(Qt.GlobalColor.darkGreen)
                    elif marker is False:
                        item.setForeground(Qt.GlobalColor.red)
                if column == 0 and record.get("error"):
                    item.setToolTip(record["error"])
                self.table.setItem(row, column, item)

    def clear_results(self):
        if self.worker is not None and self.worker.isRunning():
            self.status_label.setText("Дождитесь завершения текущей проверки")
            return
        self.records.clear()
        self.pending.clear()
        self.refresh_table()
        self.status_label.setText("Ожидаю сканирование")


class ExchangeLogBridge(QObject):
    event_received = Signal(object)


class ExchangeLogWindow(QWidget):
    """Visible, bounded log of requests and responses exchanged with SetRetailX."""

    def __init__(self, parent=None):
        super().__init__(parent)
        layout = QVBoxLayout(self)
        self.output = QPlainTextEdit()
        self.output.setReadOnly(True)
        self.output.document().setMaximumBlockCount(4000)
        layout.addWidget(self.output)

    def append_event(self, event):
        self.output.appendPlainText(
            f"[{event['timestamp']}] {event['title']}\n{event['details']}\n" + "─" * 72
        )
        scrollbar = self.output.verticalScrollBar()
        scrollbar.setValue(scrollbar.maximum())


class LongTaskRunner(QThread):
    completed = Signal(str, str)

    def __init__(self, store, client, task_id, parent=None):
        super().__init__(parent)
        self.store = store
        self.client = client
        self.task_id = task_id

    def run(self):
        try:
            task_functions = {
                DISCOVERY_TASK_ID: run_candidate_discovery,
                SNAPSHOT_TASK_ID: run_price_snapshots,
            }
            result = task_functions[self.task_id](self.store, self.client)
            self.store.finish(self.task_id, "Успешно", result)
            self.completed.emit("Успешно", result)
        except Exception as error:
            result = f"{type(error).__name__}: {error}"
            self.store.finish(self.task_id, "Ошибка", result)
            self.completed.emit("Ошибка", result)


class LongTasksWindow(QWidget):
    """Controls persisted schedules and shows the latest price-monitor results."""

    def __init__(self, printer):
        super().__init__()
        self.printer = printer
        self.store = printer.task_store
        self.runner = None
        layout = QVBoxLayout(self)
        controls = QHBoxLayout()
        self.task_select = QComboBox()
        self.task_select.addItem("Поиск NearlyPriceChanging", DISCOVERY_TASK_ID)
        self.task_select.addItem("Снимки и сравнение цен", SNAPSHOT_TASK_ID)
        self.task_select.currentIndexChanged.connect(self.on_task_selected)
        controls.addWidget(self.task_select)
        self.enabled = QCheckBox("Включена")
        controls.addWidget(self.enabled)
        controls.addWidget(QLabel("Интервал, минут:"))
        self.interval = QSpinBox()
        self.interval.setRange(1, 10080)
        controls.addWidget(self.interval)
        self.save_button = QPushButton("Сохранить расписание")
        self.save_button.clicked.connect(self.save_schedule)
        controls.addWidget(self.save_button)
        self.run_button = QPushButton("Запустить сейчас")
        self.run_button.clicked.connect(self.run_now)
        controls.addWidget(self.run_button)
        controls.addStretch()
        layout.addLayout(controls)
        self.status_label = QLabel()
        self.status_label.setWordWrap(True)
        layout.addWidget(self.status_label)
        history = QGroupBox("История изменений цен")
        history_layout = QVBoxLayout(history)
        filters = QHBoxLayout()
        filters.addWidget(QLabel("Поиск товара:"))
        self.change_search = QLineEdit()
        self.change_search.setPlaceholderText("Артикул, название или штрихкод")
        self.change_search.textChanged.connect(self._refresh_changes)
        filters.addWidget(self.change_search, 1)
        filters.addWidget(QLabel("Тип цены:"))
        self.price_type_filter = QComboBox()
        self.price_type_filter.addItem("Все", "all")
        self.price_type_filter.addItem("Обычная", "pricesMap")
        self.price_type_filter.addItem("99", "prices99Map")
        self.price_type_filter.currentIndexChanged.connect(self._refresh_changes)
        filters.addWidget(self.price_type_filter)
        history_layout.addLayout(filters)
        self.changes_count = QLabel()
        history_layout.addWidget(self.changes_count)
        self.changes = QTableWidget(0, 7)
        self.changes.setHorizontalHeaderLabels(
            ["Когда", "Артикул", "Товар", "Штрихкод", "Тип цены", "Было", "Стало"]
        )
        self.changes.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.changes.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.changes.verticalHeader().setVisible(False)
        header = self.changes.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(4, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(5, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(6, QHeaderView.ResizeMode.ResizeToContents)
        history_layout.addWidget(self.changes, 1)
        layout.addWidget(history, 1)
        self.timer = QTimer(self)
        self.timer.setInterval(5000)
        self.timer.timeout.connect(self.poll_scheduler)
        self.refresh()

    def start(self):
        self.timer.start()
        self.poll_scheduler()

    def save_schedule(self):
        self.store.configure(
            self.task_select.currentData(),
            self.enabled.isChecked(),
            self.interval.value() * 60,
        )
        self.refresh()
        self.poll_scheduler()

    def run_now(self):
        self._start_task(force=True)

    def poll_scheduler(self):
        if self.runner is not None:
            return
        task = self.store.claim_due()
        if task is not None:
            self._launch(task)

    def _start_task(self, force=False):
        if self.runner is not None:
            return
        task_id = self.task_select.currentData()
        task = self.store.force_claim(task_id) if force else self.store.claim_due()
        if task is not None:
            self._launch(task)

    def _launch(self, _task):
        client = self.printer.setretail_client
        if client is None:
            self.store.finish(
                _task["task_id"], "Ошибка", "Нет активной сессии SetRetailX."
            )
            self.refresh()
            return
        self.runner = LongTaskRunner(self.store, client, _task["task_id"], self)
        self.runner.completed.connect(self.task_completed)
        self.runner.finished.connect(self.runner_finished)
        self.runner.finished.connect(self.runner.deleteLater)
        self.run_button.setEnabled(False)
        self.status_label.setText("Задача выполняется…")
        self.runner.start()

    @Slot(str, str)
    def task_completed(self, status, result):
        self.refresh()

    @Slot()
    def runner_finished(self):
        self.runner = None
        self.run_button.setEnabled(True)
        self.refresh()

    def refresh(self):
        tasks = self.store.list_tasks()
        self.tasks_by_id = {item["task_id"]: item for item in tasks}
        self.refresh_task_controls()
        self._refresh_changes()

    def on_task_selected(self, *_args):
        self.refresh_task_controls()
        self._refresh_changes()

    def _refresh_changes(self, *_args):
        if not hasattr(self, "tasks_by_id"):
            return
        changes = self.store.recent_changes(limit=1000)
        query = self.change_search.text().strip().casefold()
        selected_price_type = self.price_type_filter.currentData()
        entries = []
        for change in changes:
            try:
                old_prices = json.loads(change["old_prices_json"])
                new_prices = json.loads(change["new_prices_json"])
            except (TypeError, ValueError):
                old_prices, new_prices = {}, {}
            for label, key in (("Обычные", "pricesMap"), ("99", "prices99Map")):
                if selected_price_type not in ("all", key):
                    continue
                old_map = old_prices.get(key, {})
                new_map = new_prices.get(key, {})
                for price_number in sorted(set(old_map) | set(new_map)):
                    old_value = old_map.get(price_number, "—")
                    new_value = new_map.get(price_number, "—")
                    if old_value != new_value:
                        searchable = " ".join(
                            str(change.get(field, ""))
                            for field in ("marking", "name", "barcode")
                        ).casefold()
                        if query and query not in searchable:
                            continue
                        entries.append(
                            (
                                change["detected_at"],
                                datetime.fromtimestamp(change["detected_at"])
                                .astimezone()
                                .strftime("%d.%m.%Y %H:%M:%S"),
                                change["marking"],
                                change["name"],
                                change["barcode"],
                                f"{label} #{price_number}",
                                old_value,
                                new_value,
                            )
                        )
        entries.sort(key=lambda entry: entry[0], reverse=True)
        self.changes.setRowCount(len(entries))
        for row, entry in enumerate(entries):
            for column, value in enumerate(entry[1:]):
                self.changes.setItem(row, column, QTableWidgetItem(str(value)))
        self.changes_count.setText(
            f"Показано изменений цен: {len(entries)}"
            + (" · просмотрены последние 1000 событий" if len(changes) >= 1000 else "")
        )

    def refresh_task_controls(self, *_args):
        if not hasattr(self, "tasks_by_id"):
            return
        task = self.tasks_by_id.get(self.task_select.currentData())
        if task is not None:
            self.enabled.setChecked(bool(task["enabled"]))
            self.interval.setValue(max(1, int(task["interval_seconds"]) // 60))
            next_run = (
                datetime.fromtimestamp(task["next_run_at"])
                .astimezone()
                .strftime("%Y-%m-%d %H:%M:%S")
                if task["next_run_at"]
                else "не запланирован"
            )
            last_run = (
                datetime.fromtimestamp(task["last_finished_at"])
                .astimezone()
                .strftime("%Y-%m-%d %H:%M:%S")
                if task["last_finished_at"]
                else "ещё не выполнялась"
            )
            self.status_label.setText(
                f"Статус: {task['last_status']} · последнее выполнение: {last_run} · "
                f"следующий запуск: {next_run}\nРезультат: {task['last_result'] or '—'}"
            )


class ScannerWindow(QWidget):
    """Barcode scanning and format assignment controls."""

    def __init__(self, printer):
        super().__init__()
        self.printer = printer

        layout = QVBoxLayout(self)
        form = QFormLayout()
        self.barcode_input = QLineEdit()
        self.barcode_input.setPlaceholderText("Сканируйте или введите штрихкод")
        self.barcode_input.returnPressed.connect(self.lookup_barcode)
        form.addRow("Штрихкод:", self.barcode_input)

        self.format_combo = QComboBox()
        for key, info in PRINT_FORMATS.items():
            self.format_combo.addItem(info["name"], key)
        form.addRow("Формат:", self.format_combo)

        self.amount_spin = QSpinBox()
        self.amount_spin.setRange(1, 999)
        form.addRow("Тираж:", self.amount_spin)
        layout.addLayout(form)

        buttons = QHBoxLayout()
        self.lookup_button = QPushButton("Найти и добавить")
        self.lookup_button.clicked.connect(self.lookup_barcode)
        buttons.addWidget(self.lookup_button)
        self.assign_all_button = QPushButton("Назначить всем неназначенным")
        self.assign_all_button.clicked.connect(self.assign_unassigned)
        buttons.addWidget(self.assign_all_button)
        buttons.addStretch()
        layout.addLayout(buttons)
        layout.addStretch()

        self.status_label = QLabel("Подключаемся к SetRetailX…")
        self.status_label.setWordWrap(True)
        layout.addWidget(self.status_label)

    def set_busy(self, busy, message=None):
        self.lookup_button.setEnabled(not busy)
        self.barcode_input.setEnabled(not busy)
        self.assign_all_button.setEnabled(not busy)
        if message:
            self.status_label.setText(message)

    def lookup_barcode(self):
        barcode = retail.extract_barcode(self.barcode_input.text())
        if not barcode or retail.active_session is None:
            return
        self.barcode_input.clear()
        self.set_busy(True, f"Ищем товар {barcode}…")
        self.printer.start_task(
            lambda: retail.lookup_product(barcode),
            lambda product: self.product_found(product, barcode),
        )

    def product_found(self, product, barcode):
        if not product:
            self.set_busy(False, f"Штрихкод {barcode} не найден.")
            self.barcode_input.setFocus()
            return
        entry = {
            "st_code": product["st_code"],
            "name": product["name"],
            "vendor_code": product["vendor_code"],
            "uom": product.get("uom") or "ST",
            "barcode": product["barcode"],
            "source": product["source"],
            "format": None,
            "amount": None,
            "quantity": 1,
        }
        self.printer.entries.append(entry)
        self.printer.refresh_queue()
        source = "локальная база" if product["source"] == "offline" else "онлайн"
        self.set_busy(False, f"Добавлен товар из {source}: {product['name']}")
        self.barcode_input.setFocus()

    def assign_unassigned(self):
        key = self.format_combo.currentData()
        amount = self.amount_spin.value()
        entries = [entry for entry in self.printer.entries if not entry["format"]]
        for entry in entries:
            entry["format"] = key
            entry["amount"] = amount
        self.printer.refresh_queue()
        self.status_label.setText(f"Назначено неназначенных товаров: {len(entries)}")


class ProductSearchWindow(QWidget):
    """Search the local product catalog and add selected results to the queue."""

    def __init__(self, printer):
        super().__init__()
        self.printer = printer

        layout = QVBoxLayout(self)
        search_row = QHBoxLayout()
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("Название, артикул или штрихкод")
        self.search_input.returnPressed.connect(self.search)
        search_row.addWidget(self.search_input, 1)
        self.search_button = QPushButton("Найти")
        self.search_button.clicked.connect(self.search)
        search_row.addWidget(self.search_button)
        layout.addLayout(search_row)

        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(
            ["Товар", "Артикул", "Штрихкод", "Цена", "Остаток"]
        )
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QTableWidget.SelectionMode.ExtendedSelection)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().setVisible(False)
        self.table.setAlternatingRowColors(True)
        header = self.table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        for column in range(1, 5):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        self.table.itemSelectionChanged.connect(self.show_product_details)
        self.table.cellClicked.connect(self.open_product_card)
        layout.addWidget(self.table, 1)

        self.details = QLabel("Введите запрос для поиска в локальной базе.")
        self.details.setWordWrap(True)
        layout.addWidget(self.details)

        footer = QHBoxLayout()
        self.result_label = QLabel("")
        footer.addWidget(self.result_label, 1)
        self.add_button = QPushButton("Добавить выбранные в очередь")
        self.add_button.clicked.connect(self.add_selected)
        self.add_button.setEnabled(False)
        footer.addWidget(self.add_button)
        layout.addLayout(footer)

    def set_busy(self, busy):
        self.search_input.setEnabled(not busy)
        self.search_button.setEnabled(not busy)
        if busy:
            self.add_button.setEnabled(False)
        else:
            self.add_button.setEnabled(bool(self.table.selectedItems()))

    def search(self):
        query = self.search_input.text().strip()
        if not query:
            self.result_label.setText("Введите название, артикул или штрихкод.")
            return
        self.printer.set_busy(True, "Ищем товары в локальной базе…")
        self.details.setText("Ищем в локальной базе…")
        self.printer.start_task(
            lambda: retail.search_products(query),
            self.search_finished,
        )

    def search_finished(self, products):
        self.products = products
        self.table.setRowCount(len(products))
        for row, product in enumerate(products):
            price = product.get("price")
            quantity = product.get("total_qty")
            values = [
                product["name"],
                product["vendor_code"],
                product["barcode"],
                _format_price(price),
                "—" if quantity is None else quantity,
            ]
            for column, value in enumerate(values):
                item = QTableWidgetItem(str(value))
                if column in (3, 4):
                    item.setTextAlignment(
                        Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
                    )
                if column == 0:
                    item.setData(Qt.ItemDataRole.UserRole, row)
                self.table.setItem(row, column, item)
        self.printer.set_busy(False)
        self.result_label.setText(f"Найдено товаров: {len(products)}")
        self.details.setText("Выберите товар, чтобы посмотреть описание.")
        self.add_button.setEnabled(False)

    def show_product_details(self):
        rows = sorted({item.row() for item in self.table.selectedItems()})
        self.add_button.setEnabled(bool(rows))
        if len(rows) != 1:
            self.details.setText(
                f"Выбрано товаров: {len(rows)}"
                if rows
                else "Выберите товар, чтобы посмотреть описание."
            )
            return
        product = self.products[rows[0]]
        description = product.get("description") or "Описание отсутствует."
        price = product.get("price")
        quantity = product.get("total_qty")
        self.details.setText(
            f"{product['name']}\n"
            f"Артикул: {product['vendor_code'] or '—'} · "
            f"Штрихкод: {product['barcode'] or '—'}\n"
            f"Цена: {_format_price(price)} · "
            f"Остаток: {quantity if quantity is not None else '—'}\n{description}"
        )

    def open_product_card(self, row, _column):
        if not 0 <= row < len(self.products):
            return
        self.printer.show_product_card(self.products[row])

    def add_selected(self):
        rows = sorted({item.row() for item in self.table.selectedItems()})
        if not rows:
            return
        for row in rows:
            product = self.products[row]
            self.printer.entries.append(
                {
                    "st_code": product["st_code"],
                    "name": product["name"],
                    "vendor_code": product["vendor_code"],
                    "uom": product.get("uom") or "ST",
                    "barcode": product["barcode"],
                    "source": product["source"],
                    "format": None,
                    "amount": None,
                    "quantity": 1,
                }
            )
        self.printer.refresh_queue()
        self.printer.statusBar().showMessage(
            f"Добавлено товаров в очередь: {len(rows)}", 5000
        )


class ProductCardWindow(QWidget):
    """Readable product card with async image loading and parsed attributes."""

    def __init__(self, printer, product):
        super().__init__()
        self.printer = printer
        self.product = product
        self.setMinimumSize(620, 500)
        details = product.get("details", product)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(12)

        self.title_label = QLabel(product.get("name") or "Без названия")
        self.title_label.setWordWrap(True)
        self.title_label.setStyleSheet("font-size: 22px; font-weight: 700;")
        layout.addWidget(self.title_label)

        self.category_label = QLabel(
            retail.product_category(product) or "Категория не указана"
        )
        self.category_label.setWordWrap(True)
        self.category_label.setStyleSheet("color: palette(mid); font-size: 13px;")
        layout.addWidget(self.category_label)

        content = QHBoxLayout()
        content.setSpacing(18)
        self.image_frame = QFrame()
        self.image_frame.setFrameShape(QFrame.Shape.StyledPanel)
        self.image_frame.setMinimumSize(270, 310)
        self.image_frame.setMaximumWidth(360)
        image_layout = QVBoxLayout(self.image_frame)
        self.image_label = QLabel("Загружаем фото…")
        self.image_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.image_label.setMinimumSize(240, 280)
        self.image_label.setWordWrap(True)
        image_layout.addWidget(self.image_label)
        content.addWidget(self.image_frame, 1)

        info = QVBoxLayout()
        stats = QGridLayout()
        stats.setSpacing(10)
        self.price_label = self._stat_card(
            "Цена", self._display_price(product.get("price"))
        )
        self.stock_label = self._stat_card(
            "Остаток", self._display_stock(product.get("total_qty"))
        )
        stats.addWidget(self.price_label, 0, 0)
        stats.addWidget(self.stock_label, 0, 1)
        info.addLayout(stats)

        description = (
            product.get("description") or details.get("description") or ""
        ).strip()
        if description:
            description_title = QLabel("Описание")
            description_title.setStyleSheet("font-weight: 600; margin-top: 8px;")
            info.addWidget(description_title)
            self.description_label = QLabel(description)
            self.description_label.setWordWrap(True)
            self.description_label.setTextInteractionFlags(
                Qt.TextInteractionFlag.TextSelectableByMouse
            )
            info.addWidget(self.description_label)

        specs = QGroupBox("Реквизиты товара")
        form = QFormLayout(specs)
        for label, value in (
            ("Артикул", product.get("vendor_code")),
            ("Штрихкод", product.get("barcode")),
            ("Код SetRetailX", product.get("st_code")),
            ("Ед. измерения", product.get("uom") or "ST"),
        ):
            if value not in (None, ""):
                value_label = QLabel(str(value))
                value_label.setTextInteractionFlags(
                    Qt.TextInteractionFlag.TextSelectableByMouse
                )
                form.addRow(label + ":", value_label)
        known_attributes = {
            "brand": "Бренд",
            "brandname": "Бренд",
            "manufacturer": "Производитель",
            "manufacturername": "Производитель",
            "model": "Модель",
            "color": "Цвет",
            "colorname": "Цвет",
            "size": "Размер",
            "sizename": "Размер",
            "material": "Материал",
            "composition": "Состав",
            "country": "Страна производства",
            "countryoforigin": "Страна производства",
            "season": "Сезон",
            "weight": "Вес",
            "volume": "Объём",
            "cost": "Себестоимость",
            "warranty": "Гарантия",
            "series": "Серия",
            "collection": "Коллекция",
        }
        for key, value in details.items():
            normalized = retail._normalized_field_name(key)
            label = known_attributes.get(normalized)
            if label and value not in (None, "", [], {}, ()):
                value_text = (
                    ", ".join(map(str, value))
                    if isinstance(value, (list, tuple))
                    else str(value)
                )
                value_label = QLabel(value_text)
                value_label.setWordWrap(True)
                value_label.setTextInteractionFlags(
                    Qt.TextInteractionFlag.TextSelectableByMouse
                )
                form.addRow(label + ":", value_label)
        info.addWidget(specs)
        info.addStretch(1)
        content.addLayout(info, 2)
        layout.addLayout(content, 1)

        self.add_button = QPushButton("Добавить в очередь печати")
        self.add_button.clicked.connect(self.add_to_queue)
        layout.addWidget(self.add_button)

        image_url = retail.product_image_url(product)
        if not image_url:
            self.image_label.setText("Для этого товара нет фотографии")
        else:
            self.image_thread = TaskThread(
                lambda: retail.download_product_image(image_url), self
            )
            self.image_thread.succeeded.connect(self.show_image)
            self.image_thread.failed.connect(self.show_image_error)
            self.image_thread.start()

    @staticmethod
    def _display_price(value):
        return _format_price(value)

    @staticmethod
    def _display_stock(value):
        return "—" if value in (None, "") else str(value)

    @staticmethod
    def _stat_card(title, value):
        card = QFrame()
        card.setFrameShape(QFrame.Shape.StyledPanel)
        card.setMinimumWidth(110)
        box = QVBoxLayout(card)
        box.setContentsMargins(10, 8, 10, 8)
        caption = QLabel(title)
        caption.setStyleSheet("color: palette(mid);")
        value_label = QLabel(value)
        value_label.setStyleSheet("font-size: 17px; font-weight: 700;")
        value_label.setWordWrap(True)
        box.addWidget(caption)
        box.addWidget(value_label)
        card.value_label = value_label
        return card

    def show_image(self, image_data):
        pixmap = QPixmap()
        if not pixmap.loadFromData(image_data):
            self.show_image_error("Не удалось распознать файл изображения.")
            return
        self.image_label.setText("")
        self.image_label.setPixmap(
            pixmap.scaled(
                QSize(340, 360),
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
        )

    def show_image_error(self, _error):
        self.image_label.setPixmap(QPixmap())
        self.image_label.setText("Фото товара временно недоступно")

    def add_to_queue(self):
        product = self.product
        self.printer.entries.append(
            {
                "st_code": product["st_code"],
                "name": product["name"],
                "vendor_code": product["vendor_code"],
                "uom": product.get("uom") or "ST",
                "barcode": product["barcode"],
                "source": product["source"],
                "format": None,
                "amount": None,
                "quantity": 1,
            }
        )
        self.printer.refresh_queue()
        self.printer.statusBar().showMessage(
            f"Добавлен товар в очередь: {product['name']}", 5000
        )


class PrintHistoryWindow(QWidget):
    """Local archive of submitted print batches with queue restoration."""

    def __init__(self, printer):
        super().__init__()
        self.printer = printer
        layout = QVBoxLayout(self)
        self.table = QTableWidget(0, 4)
        self.table.setHorizontalHeaderLabels(["№", "Дата", "Статус", "Товаров"])
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().setVisible(False)
        self.table.setAlternatingRowColors(True)
        header = self.table.horizontalHeader()
        for column in range(3):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(3, QHeaderView.ResizeMode.Stretch)
        layout.addWidget(self.table, 1)

        footer = QHBoxLayout()
        self.info_label = QLabel("Партии печати сохраняются локально.")
        footer.addWidget(self.info_label, 1)
        self.restore_button = QPushButton("Восстановить в очередь")
        self.restore_button.clicked.connect(self.restore_selected)
        footer.addWidget(self.restore_button)
        layout.addLayout(footer)
        self.refresh()

    def refresh(self):
        self.batches = self.printer.print_history.batches()
        self.table.setRowCount(len(self.batches))
        for row, batch in enumerate(self.batches):
            values = (
                batch["id"],
                batch["created_at"].replace("T", " "),
                batch["status"],
                len(batch["entries"]),
            )
            for column, value in enumerate(values):
                item = QTableWidgetItem(str(value))
                if column in (0, 3):
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.table.setItem(row, column, item)
        self.info_label.setText(f"Сохранено партий: {len(self.batches)}")
        self.restore_button.setEnabled(bool(self.batches))

    def restore_selected(self):
        rows = self.table.selectionModel().selectedRows()
        if not rows:
            self.info_label.setText("Выберите партию для восстановления.")
            return
        batch = self.batches[rows[0].row()]
        entries = batch["entries"]
        if batch["status"] == "Частично отправлено":
            successful = set(batch["successful_indexes"])
            entries = [
                entry for index, entry in enumerate(entries) if index not in successful
            ]
        self.printer.entries.extend(dict(entry) for entry in entries)
        self.printer.refresh_queue()
        self.printer.show_subwindow("queue")
        self.printer.statusBar().showMessage(
            f"Восстановлено товаров из истории: {len(entries)}", 5000
        )


class QueueWindow(QWidget):
    """Shared product queue with assignment and print actions."""

    def __init__(self, printer):
        super().__init__()
        self.printer = printer

        layout = QVBoxLayout(self)
        controls = QHBoxLayout()
        self.format_combo = QComboBox()
        for key, info in PRINT_FORMATS.items():
            self.format_combo.addItem(info["name"], key)
        controls.addWidget(QLabel("Формат:"))
        controls.addWidget(self.format_combo)
        self.amount_spin = QSpinBox()
        self.amount_spin.setRange(1, 999)
        controls.addWidget(QLabel("Тираж:"))
        controls.addWidget(self.amount_spin)
        self.assign_button = QPushButton("Назначить выбранным")
        self.assign_button.clicked.connect(self.assign_selected)
        controls.addWidget(self.assign_button)
        controls.addStretch()
        layout.addLayout(controls)

        self.table = QTableWidget(0, 7)
        self.table.setHorizontalHeaderLabels(
            ["№", "Товар", "Штрихкод", "ST-код", "Формат", "Тираж", "Количество"]
        )
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QTableWidget.SelectionMode.ExtendedSelection)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().setVisible(False)
        self.table.setAlternatingRowColors(True)
        header = self.table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        for column in (2, 3, 4, 5, 6):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self.show_queue_context_menu)
        layout.addWidget(self.table, 1)

        footer = QHBoxLayout()
        self.queue_label = QLabel("0 товаров")
        footer.addWidget(self.queue_label, 1)
        self.remove_button = QPushButton("Удалить выбранное")
        self.remove_button.clicked.connect(self.remove_selected)
        footer.addWidget(self.remove_button)
        self.clear_button = QPushButton("Очистить очередь")
        self.clear_button.clicked.connect(self.clear_queue)
        footer.addWidget(self.clear_button)
        self.print_button = QPushButton("Печать назначенных")
        self.print_button.clicked.connect(self.print_assigned_groups)
        footer.addWidget(self.print_button)
        self.print_selected_button = QPushButton("Печать выбранных")
        self.print_selected_button.clicked.connect(self.print_selected_groups)
        self.print_selected_button.setEnabled(False)
        footer.addWidget(self.print_selected_button)
        self.table.itemSelectionChanged.connect(self.update_selection_actions)
        layout.addLayout(footer)

    def refresh(self):
        entries = self.printer.entries
        self.table.setRowCount(len(entries))
        for row, entry in enumerate(entries):
            format_name = (
                PRINT_FORMATS[entry["format"]]["name"]
                if entry["format"]
                else "Не назначен"
            )
            entry.setdefault("quantity", 1)
            values = [
                row + 1,
                entry["name"],
                entry["barcode"],
                retail.product_print_code(entry),
                format_name,
                entry["amount"] or "—",
                entry["quantity"],
            ]
            for column, value in enumerate(values):
                item = QTableWidgetItem(str(value))
                if column in (0, 5, 6):
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.table.setItem(row, column, item)
        assigned = sum(bool(entry["format"]) for entry in entries)
        self.queue_label.setText(
            f"В очереди: {len(entries)} · назначено: {assigned} · без формата: {len(entries) - assigned}"
        )

    def selected_indexes(self):
        return sorted(
            {index.row() for index in self.table.selectionModel().selectedRows()}
        )

    def update_selection_actions(self):
        self.print_selected_button.setEnabled(
            bool(self.selected_indexes()) and not self.printer.is_busy
        )

    def show_queue_context_menu(self, position):
        row = self.table.rowAt(position.y())
        if row < 0:
            return

        if row not in self.selected_indexes():
            self.table.clearSelection()
            self.table.selectRow(row)

        menu = QMenu(self)
        set_amount_action = menu.addAction("Изменить тираж выбранных…")
        set_quantity_action = menu.addAction("Изменить количество выбранных…")
        selected_action = menu.exec(self.table.viewport().mapToGlobal(position))

        if selected_action == set_amount_action:
            self.change_selected_amount()
        elif selected_action == set_quantity_action:
            self.change_selected_quantity()

    def change_selected_amount(self):
        indexes = self.selected_indexes()
        if not indexes:
            return
        amount, accepted = QInputDialog.getInt(
            self,
            "Массовое изменение тиража",
            "Ценников на каждый товар:",
            1,
            1,
            999,
        )
        if not accepted:
            return
        for index in indexes:
            self.printer.entries[index]["amount"] = amount
        self.printer.refresh_queue()
        self.printer.statusBar().showMessage(
            f"Тираж {amount} применён к товарам: {len(indexes)}", 5000
        )

    def change_selected_quantity(self):
        indexes = self.selected_indexes()
        if not indexes:
            return
        first_entry = self.printer.entries[indexes[0]]
        quantity, accepted = QInputDialog.getInt(
            self,
            "Массовое изменение количества",
            "Экземпляров каждого выбранного товара:",
            first_entry.get("quantity", 1),
            1,
            999,
        )
        if not accepted:
            return
        for index in indexes:
            self.printer.entries[index]["quantity"] = quantity
        self.printer.refresh_queue()
        self.printer.statusBar().showMessage(
            f"Количество {quantity} применено к товарам: {len(indexes)}", 5000
        )

    def assign_selected(self):
        indexes = self.selected_indexes()
        if not indexes:
            self.printer.statusBar().showMessage("Выберите товары в очереди", 4000)
            return
        key = self.format_combo.currentData()
        amount = self.amount_spin.value()
        for index in indexes:
            self.printer.entries[index]["format"] = key
            self.printer.entries[index]["amount"] = amount
        self.printer.refresh_queue()
        self.printer.statusBar().showMessage(
            f"Назначено товаров: {len(indexes)} · {key}, тираж {amount}", 5000
        )

    def remove_selected(self):
        indexes = self.selected_indexes()
        for index in reversed(indexes):
            self.printer.entries.pop(index)
        self.printer.refresh_queue()
        self.printer.statusBar().showMessage(f"Удалено товаров: {len(indexes)}", 4000)

    def clear_queue(self):
        if not self.printer.entries:
            return
        answer = QMessageBox.question(
            self, "Очистить очередь", "Удалить все товары из очереди?"
        )
        if answer == QMessageBox.StandardButton.Yes:
            self.printer.entries.clear()
            self.printer.refresh_queue()
            self.printer.statusBar().showMessage("Очередь очищена", 4000)

    def print_assigned_groups(self):
        self.print_entries(self.printer.entries, "назначенных")

    def print_selected_groups(self):
        indexes = self.selected_indexes()
        if not indexes:
            self.printer.statusBar().showMessage("Выберите товары в очереди", 4000)
            return
        self.print_entries(
            [self.printer.entries[index] for index in indexes], "выбранных"
        )

    def print_entries(self, entries, description):
        candidates = [
            entry
            for entry in entries
            if entry["format"]
            and isinstance(entry.get("amount"), int)
            and 1 <= entry["amount"] <= 999
        ]
        groups = defaultdict(list)
        for index, entry in enumerate(candidates):
            groups[(entry["format"], entry["amount"])].append((index, entry))
        if not groups:
            self.printer.statusBar().showMessage(
                "Нет назначенных товаров для печати", 4000
            )
            return
        if any(not entry["format"] for entry in entries):
            QMessageBox.information(
                self,
                "Есть неназначенные товары",
                "Назначенные товары будут напечатаны. Неназначенные останутся в очереди.",
            )
        format_order = {key: index for index, key in enumerate(PRINT_FORMATS)}
        ordered_groups = sorted(
            groups.items(),
            key=lambda item: (
                format_order.get(item[0][0], len(format_order)),
                item[0][1],
            ),
        )
        try:
            batch_id = self.printer.print_history.add_batch(
                [dict(entry) for entry in candidates]
            )
        except Exception as error:
            QMessageBox.critical(
                self,
                "Не удалось сохранить историю",
                f"Задание не отправлено, потому что его не удалось сохранить локально:\n{error}",
            )
            return
        self.printer.active_print_batch_id = batch_id
        self.printer.print_history_window.refresh()
        self.printer.set_busy(True, f"Отправляем задания для {description} товаров…")
        self.printer.show_subwindow("exchange")

        def print_groups():
            successful_ids = []
            successful_indexes = []
            failures = []
            for (format_key, amount), group_items in ordered_groups:
                group_entries = [entry for _index, entry in group_items]
                try:
                    print(
                        f"[Пакет печати] формат={format_key}, "
                        f"amountToPrint={amount}, товаров={len(group_entries)}"
                    )
                    printed = retail.print_group(format_key, amount, group_entries)
                except Exception as error:
                    failures.append(str(error))
                    continue
                if printed:
                    successful_ids.extend(id(entry) for entry in group_entries)
                    successful_indexes.extend(index for index, _entry in group_items)
            return {
                "successful_ids": successful_ids,
                "successful_indexes": successful_indexes,
                "failures": failures,
            }

        self.printer.start_task(print_groups, self.printer.print_finished)


class PrinterWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.entries = []
        self.task = None
        self.is_busy = False
        self.print_history = PrintHistory()
        self.task_store = TaskStore(TASKS_DB_PATH)
        self.setretail_client = None
        self.active_print_batch_id = None
        self.subwindows = {}
        self.exchange_bridge = ExchangeLogBridge(self)
        self.exchange_log = ExchangeLogWindow()
        self.exchange_bridge.event_received.connect(self.exchange_log.append_event)
        retail.exchange_event_handler = self.exchange_bridge.event_received.emit
        auth.exchange_event_handler = self.exchange_bridge.event_received.emit
        self.setWindowTitle("SetRetailX — Печать ценников")
        self.resize(1200, 800)
        self.setMinimumSize(800, 560)

        self.mdi = QMdiArea()
        self.mdi.setViewMode(QMdiArea.ViewMode.SubWindowView)
        self.setCentralWidget(self.mdi)
        self.setStatusBar(QStatusBar())
        self.statusBar().showMessage("Подключаемся к SetRetailX…")

        self.scanner = ScannerWindow(self)
        self.price_comparison = PriceComparisonWindow(self)
        self.product_search = ProductSearchWindow(self)
        self.queue = QueueWindow(self)
        self.print_history_window = PrintHistoryWindow(self)
        self.long_tasks_window = LongTasksWindow(self)
        self._add_internal_window(
            "exchange", "Обмен с SetRetailX", self.exchange_log, (800, 450)
        )
        self._add_internal_window("scanner", "Сканирование", self.scanner, (430, 230))
        self._add_internal_window(
            "price_comparison", "Сверка ценников", self.price_comparison, (1250, 600)
        )
        self._add_internal_window(
            "search", "Поиск товаров", self.product_search, (900, 560)
        )
        self._add_internal_window("queue", "Очередь печати", self.queue, (820, 480))
        self._add_internal_window(
            "history", "История печати", self.print_history_window, (760, 420)
        )
        self._add_internal_window(
            "tasks", "Долгоживущие задачи", self.long_tasks_window, (950, 560)
        )

        self._create_menus()
        self._create_toolbar()
        self.refresh_queue()

        self.set_busy(True, "Авторизация в SetRetailX…")
        self.start_task(get_authenticated_session, self.auth_ready)

    def _add_internal_window(self, key, title, widget, size):
        subwindow = QMdiSubWindow()
        subwindow.setWidget(widget)
        subwindow.setWindowTitle(title)
        subwindow.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose, False)
        subwindow.resize(*size)
        self.mdi.addSubWindow(subwindow)
        subwindow.hide()
        self.subwindows[key] = subwindow
        return subwindow

    def _create_menus(self):
        file_menu = self.menuBar().addMenu("Файл")
        quit_action = QAction("Выход", self)
        quit_action.setShortcut("Ctrl+Q")
        quit_action.triggered.connect(self.close)
        file_menu.addAction(quit_action)

        view_menu = self.menuBar().addMenu("Вид")
        self._add_window_action(view_menu, "Сканирование", "scanner")
        self._add_window_action(view_menu, "Сверка ценников", "price_comparison")
        self._add_window_action(view_menu, "Поиск товаров", "search")
        self._add_window_action(view_menu, "Очередь печати", "queue")
        self._add_window_action(view_menu, "Обмен с SetRetailX", "exchange")
        self._add_window_action(view_menu, "История печати", "history")
        self._add_window_action(view_menu, "Долгоживущие задачи", "tasks")

        window_menu = self.menuBar().addMenu("Окна")
        window_menu.addAction("Каскадом", self.mdi.cascadeSubWindows)
        window_menu.addAction("Плиткой", self.mdi.tileSubWindows)
        window_menu.addSeparator()
        self.window_list_menu = window_menu.addMenu("Открытые окна")
        window_menu.aboutToShow.connect(self._refresh_window_menu)

        help_menu = self.menuBar().addMenu("Справка")
        help_menu.addAction("О программе", self._about)

    def _add_window_action(self, menu, title, key):
        action = QAction(title, self)
        action.triggered.connect(
            lambda checked=False, window_key=key: self.show_subwindow(window_key)
        )
        menu.addAction(action)

    def _create_toolbar(self):
        toolbar = self.addToolBar("Окна")
        toolbar.setMovable(False)
        toolbar.addAction("Сканирование", lambda: self.show_subwindow("scanner"))
        toolbar.addAction(
            "Сверка ценников", lambda: self.show_subwindow("price_comparison")
        )
        toolbar.addAction("Поиск товаров", lambda: self.show_subwindow("search"))
        toolbar.addAction("Очередь печати", lambda: self.show_subwindow("queue"))
        toolbar.addAction("Обмен с SetRetailX", lambda: self.show_subwindow("exchange"))
        toolbar.addAction("История печати", lambda: self.show_subwindow("history"))
        toolbar.addAction("Долгие задачи", lambda: self.show_subwindow("tasks"))
        toolbar.addSeparator()
        toolbar.addAction("Каскадом", self.mdi.cascadeSubWindows)
        toolbar.addAction("Плиткой", self.mdi.tileSubWindows)

    def _refresh_window_menu(self):
        self.window_list_menu.clear()
        for subwindow in self.mdi.subWindowList():
            action = self.window_list_menu.addAction(subwindow.windowTitle())
            action.setCheckable(True)
            action.setChecked(subwindow.isActiveWindow())
            action.triggered.connect(
                lambda checked=False, window=subwindow: self.mdi.setActiveSubWindow(
                    window
                )
            )

    def show_subwindow(self, key):
        subwindow = self.subwindows[key]
        self._present_subwindow(subwindow)

    def _present_subwindow(self, subwindow):
        if subwindow.isMinimized():
            subwindow.showNormal()
        widget = subwindow.widget()
        if widget is not None:
            widget.show()
        subwindow.show()
        subwindow.raise_()
        self.mdi.setActiveSubWindow(subwindow)

    def show_product_card(self, product):
        key = f"product:{product['st_code']}"
        subwindow = self.subwindows.get(key)
        if subwindow is None:
            card = ProductCardWindow(self, product)
            subwindow = self._add_internal_window(
                key,
                f"Карточка товара — {product['name']}",
                card,
                (540, 440),
            )
        self._present_subwindow(subwindow)

    def _about(self):
        QMessageBox.about(
            self,
            "О программе",
            "SetRetailX — сканирование товаров и печать ценников.",
        )

    def start_task(self, function, on_success):
        self.task = TaskThread(function, self)
        self.task.succeeded.connect(on_success)
        self.task.failed.connect(self.task_failed)
        self.task.finished.connect(self.task_finished)
        self.task.start()

    def set_busy(self, busy, message=None):
        self.is_busy = busy
        self.scanner.set_busy(busy)
        self.product_search.set_busy(busy)
        for widget in (
            self.queue.assign_button,
            self.queue.remove_button,
            self.queue.clear_button,
            self.queue.print_button,
            self.queue.print_selected_button,
        ):
            widget.setEnabled(not busy)
        self.queue.update_selection_actions()
        if message:
            self.statusBar().showMessage(message)

    def auth_ready(self, result):
        retail.active_session, retail.session_token = result
        if retail.active_session is None or retail.session_token is None:
            self.task_failed("Авторизация не вернула сессию или токен.")
            return
        self.setretail_client = SetRetailXClient(
            retail.active_session,
            retail.session_token,
            retail.exchange_event_handler,
        )
        self.long_tasks_window.refresh()
        self.long_tasks_window.start()
        if self.price_comparison.pending:
            self.price_comparison.process_pending()
        message = f"Готово · локальная база: {DB_PATH.name}"
        self.scanner.status_label.setText(message)
        self.set_busy(False, message)
        self.scanner.barcode_input.setFocus()

    def task_failed(self, error):
        if self.active_print_batch_id is not None:
            self.print_history.fail_batch(self.active_print_batch_id)
            self.active_print_batch_id = None
            self.print_history_window.refresh()
        self.scanner.status_label.setText(f"Ошибка: {error}")
        self.set_busy(False, f"Ошибка: {error}")
        if retail.active_session is None:
            self.set_busy(True)
            QMessageBox.critical(self, "Ошибка подключения", error)

    def task_finished(self):
        self.task = None

    def closeEvent(self, event):
        self.long_tasks_window.timer.stop()
        runner = self.long_tasks_window.runner
        if runner is not None and runner.isRunning():
            answer = QMessageBox.question(
                self,
                "Долгоживущая задача выполняется",
                "Дождаться завершения текущей задачи перед выходом?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.Yes,
            )
            if answer != QMessageBox.StandardButton.Yes:
                self.long_tasks_window.timer.start()
                event.ignore()
                return
            self.statusBar().showMessage("Ожидаем завершения текущей задачи…")
            runner.wait()
        event.accept()

    def refresh_queue(self):
        self.queue.refresh()

    def print_finished(self, result):
        successful_ids = result["successful_ids"]
        successful_indexes = result["successful_indexes"]
        failures = result["failures"]
        successful = set(successful_ids)
        self.entries = [entry for entry in self.entries if id(entry) not in successful]
        batch_id = self.active_print_batch_id
        if batch_id is not None:
            batch_entries = next(
                (
                    batch["entries"]
                    for batch in self.print_history.batches()
                    if batch["id"] == batch_id
                ),
                [],
            )
            if successful_indexes:
                status = (
                    "Отправлено"
                    if len(successful_indexes) == len(batch_entries) and not failures
                    else "Частично отправлено"
                )
            else:
                status = "Ошибка отправки" if failures else "Не отправлено"
            self.print_history.finish_batch(batch_id, successful_indexes, status)
            self.active_print_batch_id = None
            self.print_history_window.refresh()
        self.refresh_queue()
        count = len(successful_indexes)
        message = (
            f"Отправлено на печать товаров: {count}"
            if count
            else "Не удалось отправить задания. Очередь сохранена."
        )
        if failures:
            message += f" · ошибок: {len(failures)}"
        self.set_busy(False, message)
        self.scanner.barcode_input.setFocus()


def extract_barcode(user_input):
    """Compatibility alias for older imports from window.py."""
    return retail.extract_barcode(user_input)
