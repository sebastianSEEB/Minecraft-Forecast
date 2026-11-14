@echo off
cd /d "%~dp0"
echo Starting SkyBlock market collector + price server...
py -3 run.py -v
pause
