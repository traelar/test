@echo off
cd /d "%~dp0"
if not exist .env (
  echo.
  echo Missing backend\.env
  echo Copy .env.example to .env, add your Plaid Client ID and Secret, then run this again.
  echo.
  pause
  exit /b 1
)
node server.js
pause
