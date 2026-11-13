import os
import sys
from pathlib import Path
from dotenv import dotenv_values


# SQLite lives beside the project root when running from source, or next to the
# PyInstaller executable when running as a frozen bundle.
def _app_dir():
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    # src/printer/config.py -> src -> project root
    return Path(__file__).resolve().parents[2]


APP_DIR = _app_dir()
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
_tasks_db_path = os.environ.get("SETRETAILX_TASKS_DB_PATH")
if not _tasks_db_path:
    _tasks_db_path = dotenv_values(APP_DIR / ".env").get("SETRETAILX_TASKS_DB_PATH")
if _tasks_db_path:
    TASKS_DB_PATH = Path(_tasks_db_path).expanduser()
    if not TASKS_DB_PATH.is_absolute():
        TASKS_DB_PATH = APP_DIR / TASKS_DB_PATH
else:
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
