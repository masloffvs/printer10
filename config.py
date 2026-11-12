import os
import sys
from pathlib import Path


# SQLite lives beside the script, or in the extracted PyInstaller bundle.
APP_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
DB_PATH = APP_DIR / "all_products.sqlite"


def _user_data_dir():
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local"))
    elif sys.platform == "darwin":
        base = Path.home() / "Library/Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share"))
    return base / "SetRetailX"


DATA_DIR = _user_data_dir()
PRINT_HISTORY_PATH = DATA_DIR / "print_history.sqlite"
TASKS_DB_PATH = DATA_DIR / "printer12.db"

PRINT_FORMATS = {
    "А": {"name": "Формат А", "printer_id": 9974664, "template_id": "22766"},
    "Б": {"name": "Формат Б", "printer_id": 9974666, "template_id": "23841"},
    "Ц": {"name": "Формат Ц", "printer_id": 9975059, "template_id": "23574"},
}

FORMAT_ALIASES = {
    "A": "А",
    "А": "А",
    "B": "Б",
    "Б": "Б",
    "C": "Ц",
    "С": "Ц",
    "Ц": "Ц",
}
