@echo off
setlocal

if not exist "auth.py" (
    echo [ERROR] auth.py was not found. Put it next to p.py.
    exit /b 1
)

if not exist "products.db" (
    echo [ERROR] products.db was not found. Put it next to p.py.
    exit /b 1
)

python -m pip install -r requirements.txt
if errorlevel 1 exit /b 1

python -m PyInstaller --noconfirm --clean --onefile --windowed ^
    --name SetRetailXPrinter ^
    --add-data "products.db;." ^
    p.py
if errorlevel 1 exit /b 1

echo.
echo Build complete: dist\SetRetailXPrinter.exe
endlocal
