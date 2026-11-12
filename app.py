import sys

from PySide6.QtWidgets import QApplication, QMessageBox

from config import DB_PATH
from theme import APP_STYLESHEET
from window import PrinterWindow


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
