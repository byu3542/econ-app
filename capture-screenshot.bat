@echo off
REM Captures the current emulator screen straight into the screenshots folder.
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set ts=%%i
adb exec-out screencap -p > "C:\Android App\EconomicDashboard\screenshots\shot_%ts%.png"
echo Saved screenshots\shot_%ts%.png
