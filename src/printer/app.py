import sys

from PySide6.QtWidgets import QApplication, QMessageBox

from printer.config import DB_PATH
from printer.ui.theme import APP_STYLESHEET
from printer.ui.window import PrinterWindow


def main():
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    app.setStyleSheet(APP_STYLESHEET)
    if not DB_PATH.exists():
        QMessageBox.critical(
            None, "Не найдена база", f"Не найдена база данных:\n{DB_PATH}"
        )
        return 1
    window = PrinterWindow()
    window.show()
    return app.exec()
