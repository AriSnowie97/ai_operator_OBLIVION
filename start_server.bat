@echo off
chcp 65001 > nul
echo ========================================================
echo   Minecraft AI Camera Operator - Starting Core Server
echo ========================================================
.venv\Scripts\python.exe run_server.py
pause
