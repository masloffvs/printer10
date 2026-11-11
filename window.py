from collections import defaultdict

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtGui import QAction
from PySide6.QtWidgets import (
    QComboBox,
    QFormLayout,
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
    QPushButton,
    QSpinBox,
    QStatusBar,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

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
                entry["st_code"],
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
        groups = defaultdict(list)
        for entry in self.printer.entries:
            if entry["format"] and entry["amount"] in (1, 2):
                groups[(entry["format"], entry["amount"])].append(entry)
        if not groups:
            self.printer.statusBar().showMessage(
                "Нет назначенных товаров для печати", 4000
            )
            return
        if any(not entry["format"] for entry in self.printer.entries):
            QMessageBox.information(
                self,
                "Есть неназначенные товары",
                "Назначенные товары будут напечатаны. Остальные останутся в очереди.",
            )
        ordered_groups = sorted(
            groups.items(),
            key=lambda item: (("А", "Б", "Ц").index(item[0][0]), item[0][1]),
        )
        self.printer.set_busy(True, "Отправляем задания на печать…")

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
        self.subwindows = {}
        self.setWindowTitle("SetRetailX — Печать ценников")
        self.resize(1200, 800)
        self.setMinimumSize(800, 560)

        self.mdi = QMdiArea()
        self.mdi.setViewMode(QMdiArea.ViewMode.SubWindowView)
        self.setCentralWidget(self.mdi)
        self.setStatusBar(QStatusBar())
        self.statusBar().showMessage("Подключаемся к SetRetailX…")

        self.scanner = ScannerWindow(self)
        self.queue = QueueWindow(self)
        self._add_internal_window("scanner", "Сканирование", self.scanner, (430, 230))
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
        self._add_window_action(view_menu, "Очередь печати", "queue")

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
        toolbar.addAction("Очередь печати", lambda: self.show_subwindow("queue"))
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
        self.scanner.set_busy(busy)
        for widget in (
            self.queue.assign_button,
            self.queue.remove_button,
            self.queue.clear_button,
            self.queue.print_button,
        ):
            widget.setEnabled(not busy)
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
