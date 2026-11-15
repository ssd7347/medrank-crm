@echo off
rem The demo CRM website on http://localhost:3094, talking to the demo server on port 8094. Needs Node.js.
rem Stop any other copy of the website first: two copies cannot run from the same folder at once.
set "BACKEND_URL=http://localhost:8094"
cd /d "%~dp0..\frontend"
call npm run dev -- --port 3094
