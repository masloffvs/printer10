from collections import defaultdict

from PySide6.QtCore import QObject, Qt, QThread, Signal
from PySide6.QtGui import QAction
from PySide6.QtWidgets import (
    QComboBox,
    QFormLayout,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QLineEdit,
    QListWidget,
    QMainWindow,
    QMdiArea,
    QMdiSubWindow,
    QMenu,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QSpinBox,
    QStatusBar,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

import auth
from auth import get_authenticated_session
import retail
from config import DB_PATH, PRINT_FORMATS


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
        self.amount_spin.setRange(1, 2)
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
                "—" if price is None else price,
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
            f"Цена: {price if price is not None else '—'} · "
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
    """Detailed view of one local catalog product."""

    def __init__(self, printer, product):
        super().__init__()
        self.printer = printer
        self.product = product
        self.setMinimumSize(440, 320)

        layout = QVBoxLayout(self)
        details = product.get("details", product)
        self.title_label = QLabel(product.get("name") or "Без названия")
        self.title_label.setWordWrap(True)
        self.title_label.setStyleSheet("font-size: 18px; font-weight: bold;")
        layout.addWidget(self.title_label)

        self.fields = QListWidget()
        preferred = (
            ("vendor_code", "Артикул"),
            ("barcode", "Штрихкод"),
            ("price", "Цена"),
            ("cost", "Себестоимость"),
            ("total_qty", "Остаток"),
            ("product_id", "ID товара"),
            ("description", "Описание"),
        )
        shown = set()
        for key, label in preferred:
            value = details.get(key)
            if value not in (None, ""):
                self.fields.addItem(f"{label}: {value}")
                shown.add(key)
        for key, value in details.items():
            if key not in shown and value not in (None, ""):
                self.fields.addItem(f"{key}: {value}")
        layout.addWidget(self.fields, 1)

        self.add_button = QPushButton("Добавить в очередь печати")
        self.add_button.clicked.connect(self.add_to_queue)
        layout.addWidget(self.add_button)

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
        self.amount_spin.setRange(1, 2)
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
            2,
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
        groups = defaultdict(list)
        for entry in entries:
            if entry["format"] and entry["amount"] in (1, 2):
                groups[(entry["format"], entry["amount"])].append(entry)
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
        ordered_groups = sorted(
            groups.items(),
            key=lambda item: (("А", "Б", "Ц").index(item[0][0]), item[0][1]),
        )
        self.printer.set_busy(True, f"Отправляем задания для {description} товаров…")
        self.printer.show_subwindow("exchange")

        def print_groups():
            successful_ids = []
            for (format_key, amount), group_entries in ordered_groups:
                if retail.print_group(format_key, amount, group_entries):
                    successful_ids.extend(id(entry) for entry in group_entries)
            return successful_ids

        self.printer.start_task(print_groups, self.printer.print_finished)


class PrinterWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.entries = []
        self.task = None
        self.is_busy = False
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
        self.product_search = ProductSearchWindow(self)
        self.queue = QueueWindow(self)
        self._add_internal_window(
            "exchange", "Обмен с SetRetailX", self.exchange_log, (800, 450)
        )
        self.subwindows["exchange"].hide()
        self._add_internal_window("scanner", "Сканирование", self.scanner, (430, 230))
        self._add_internal_window(
            "search", "Поиск товаров", self.product_search, (900, 560)
        )
        self._add_internal_window("queue", "Очередь печати", self.queue, (820, 480))

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
        subwindow.show()
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
        self._add_window_action(view_menu, "Поиск товаров", "search")
        self._add_window_action(view_menu, "Очередь печати", "queue")
        self._add_window_action(view_menu, "Обмен с SetRetailX", "exchange")

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
        toolbar.addAction("Поиск товаров", lambda: self.show_subwindow("search"))
        toolbar.addAction("Очередь печати", lambda: self.show_subwindow("queue"))
        toolbar.addAction("Обмен с SetRetailX", lambda: self.show_subwindow("exchange"))
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
        subwindow.show()
        subwindow.raise_()
        self.mdi.setActiveSubWindow(subwindow)

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
        message = f"Готово · локальная база: {DB_PATH.name}"
        self.scanner.status_label.setText(message)
        self.set_busy(False, message)
        self.scanner.barcode_input.setFocus()

    def task_failed(self, error):
        self.scanner.status_label.setText(f"Ошибка: {error}")
        self.set_busy(False, f"Ошибка: {error}")
        if retail.active_session is None:
            self.set_busy(True)
            QMessageBox.critical(self, "Ошибка подключения", error)

    def task_finished(self):
        self.task = None

    def refresh_queue(self):
        self.queue.refresh()

    def print_finished(self, successful_ids):
        successful = set(successful_ids)
        self.entries = [entry for entry in self.entries if id(entry) not in successful]
        self.refresh_queue()
        count = len(successful_ids)
        message = (
            f"Отправлено на печать товаров: {count}"
            if count
            else "Не удалось отправить задания. Очередь сохранена."
        )
        self.set_busy(False, message)
        self.scanner.barcode_input.setFocus()


def extract_barcode(user_input):
    """Compatibility alias for older imports from window.py."""
    return retail.extract_barcode(user_input)
