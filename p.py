"""PyInstaller entry point for the SetRetailX printer app."""

import sys
from pathlib import Path

# Make the source package discoverable when running from the project root.
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "src"))

from printer.app import main


if __name__ == "__main__":
    raise SystemExit(main())
