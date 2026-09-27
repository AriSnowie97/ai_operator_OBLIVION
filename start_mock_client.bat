@echo off
chcp 65001 > nul
echo ========================================================
echo   Minecraft AI Camera Operator - Starting Mock Client
echo ========================================================
.venv\Scripts\python.exe run_mock_client.py
pause
