"""Application-wide light, FlatLaf-inspired Qt widget styling."""

APP_STYLESHEET = """
QWidget {
    color: #202124;
    font-family: "Segoe UI", "Noto Sans", sans-serif;
    font-size: 10pt;
}

QMainWindow, QDialog, QWidget#qt_scrollarea_viewport {
    background: #f4f4f4;
}

QMenuBar, QMenu {
    background: #f4f4f4;
    border: 0;
}
QMenuBar::item {
    padding: 7px 10px;
    background: transparent;
}
QMenuBar::item:selected, QMenu::item:selected {
    background: #e5effa;
    color: #172b44;
}
QMenu {
    border: 1px solid #c9cdd2;
    padding: 4px;
}
QMenu::item { padding: 6px 26px 6px 10px; }

QToolBar {
    background: #f4f4f4;
    border: 0;
    border-bottom: 1px solid #d4d6d9;
    spacing: 4px;
    padding: 5px 7px;
}
QToolBar QToolButton {
    background: transparent;
    border: 1px solid transparent;
    border-radius: 4px;
    padding: 6px 9px;
}
QToolBar QToolButton:hover { background: #e8edf3; border-color: #d5dbe2; }
QToolBar QToolButton:pressed { background: #dce8f5; }

QPushButton {
    background: #ffffff;
    border: 1px solid #bfc4ca;
    border-radius: 4px;
    padding: 6px 12px;
    min-height: 18px;
}
QPushButton:hover { background: #f5f9fd; border-color: #6b9fd6; }
QPushButton:pressed { background: #e5effa; }
QPushButton:disabled { color: #969ba1; background: #f0f0f0; border-color: #d8dadd; }

QLineEdit, QPlainTextEdit, QTextEdit, QSpinBox, QDoubleSpinBox, QComboBox {
    background: #ffffff;
    border: 1px solid #bfc4ca;
    border-radius: 3px;
    padding: 5px 7px;
    selection-background-color: #3584e4;
    selection-color: #ffffff;
}
QLineEdit:focus, QPlainTextEdit:focus, QTextEdit:focus,
QSpinBox:focus, QDoubleSpinBox:focus, QComboBox:focus {
    border: 1px solid #3584e4;
}
QComboBox::drop-down {
    border: 0;
    border-left: 1px solid #d5d8dc;
    width: 23px;
}
QComboBox QAbstractItemView {
    background: #ffffff;
    border: 1px solid #bfc4ca;
    selection-background-color: #dceafa;
    selection-color: #202124;
}

QCheckBox { spacing: 7px; }
QCheckBox::indicator {
    width: 16px;
    height: 16px;
    background: #ffffff;
    border: 1px solid #aeb4bb;
    border-radius: 3px;
}
QCheckBox::indicator:hover { border-color: #3584e4; }
QCheckBox::indicator:checked {
    background: #3584e4;
    border-color: #3584e4;
}

QTabWidget::pane {
    background: #f4f4f4;
    border: 1px solid #cdd1d6;
    top: -1px;
}
QTabBar::tab {
    background: transparent;
    border: 0;
    border-bottom: 2px solid transparent;
    padding: 8px 16px;
    margin-right: 2px;
}
QTabBar::tab:hover { background: #e9edf2; }
QTabBar::tab:selected {
    color: #1d5f9f;
    border-bottom: 2px solid #3584e4;
}

QTableWidget, QTreeWidget, QListWidget {
    background: #ffffff;
    alternate-background-color: #f7f8fa;
    border: 1px solid #c9cdd2;
    gridline-color: #e5e7e9;
    selection-background-color: #dceafa;
    selection-color: #172b44;
}
QHeaderView::section {
    background: #f0f1f2;
    color: #41464d;
    border: 0;
    border-right: 1px solid #d8dbde;
    border-bottom: 1px solid #c9cdd2;
    padding: 7px 8px;
    font-weight: 600;
}
QTableCornerButton::section { background: #f0f1f2; border: 0; }

QGroupBox {
    background: #f7f7f7;
    border: 1px solid #cdd1d6;
    border-radius: 4px;
    margin-top: 10px;
    padding: 10px 8px 8px 8px;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 10px;
    padding: 0 5px;
    color: #4a5057;
}
QStatusBar { background: #f4f4f4; border-top: 1px solid #d4d6d9; }
QStatusBar::item { border: 0; }
QScrollBar:vertical { background: #f1f2f3; width: 13px; margin: 0; }
QScrollBar:horizontal { background: #f1f2f3; height: 13px; margin: 0; }
QScrollBar::handle:vertical, QScrollBar::handle:horizontal {
    background: #c6cbd1;
    border-radius: 5px;
    min-height: 24px;
    min-width: 24px;
    margin: 2px;
}
QScrollBar::handle:vertical:hover, QScrollBar::handle:horizontal:hover { background: #aeb5bd; }
QScrollBar::add-line, QScrollBar::sub-line { width: 0; height: 0; }
QMdiArea { background: #e9eaec; }
QMdiSubWindow { background: #f4f4f4; }
"""
