@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
  echo Python 3.11 or newer is required. Install it from https://www.python.org/downloads/
  pause
  exit /b 1
)
py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3,11) else 1)"
if errorlevel 1 (
  echo Python 3.11 or newer is required. Install it from https://www.python.org/downloads/
  pause
  exit /b 1
)
if not exist ".venv\Scripts\python.exe" (
  echo Creating an isolated AirPad Python environment...
  py -3 -m venv .venv
  if errorlevel 1 (
    echo Could not create the Python virtual environment.
    pause
    exit /b 1
  )
)
echo Checking AirPad desktop companion dependencies...
.venv\Scripts\python.exe -m pip install --disable-pip-version-check -r requirements.txt
if errorlevel 1 (
  echo Dependency installation failed. Check your internet connection and try again.
  pause
  exit /b 1
)
.venv\Scripts\python.exe host.py
pause
