@echo off
setlocal

if not exist "products.db" (
    echo [ERROR] products.db was not found. Put it next to p.py.
    exit /b 1
)

if not exist ".env" (
    echo [ERROR] .env was not found. Copy .env.example to .env and fill in credentials.
    exit /b 1
)

python -m pip install -r requirements.txt
if errorlevel 1 exit /b 1

python -m PyInstaller --noconfirm --clean --onefile --windowed ^
    --name SetRetailXPrinter ^
    --add-data "products.db;." ^
    --paths src ^
    p.py
if errorlevel 1 exit /b 1

copy /Y ".env" "dist\.env" >nul
if errorlevel 1 exit /b 1

echo.
echo Build complete: dist\SetRetailXPrinter.exe
endlocal
