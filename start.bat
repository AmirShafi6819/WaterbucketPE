@echo off
rem WaterbucketPE start script (Windows)
rem Starts the proxy and restarts it automatically if it exits.

cd /d "%~dp0"

set JAR_NAME=Waterbucket.jar

java -version >nul 2>&1
if errorlevel 1 (
    echo WaterbucketPE requires Java 17 or newer. Please install it and add it to PATH.
    pause
    exit /b 1
)

:loop
java -Xms512M -Xmx1G ^
     -XX:+UseG1GC ^
     -XX:MaxGCPauseMillis=200 ^
     -XX:G1NewSizePercent=30 ^
     -XX:G1MaxNewSizePercent=40 ^
     -XX:G1ReservePercent=20 ^
     -XX:G1HeapRegionSize=8M ^
     -XX:+ParallelRefProcEnabled ^
     -XX:+UseStringDeduplication ^
     -jar %JAR_NAME% nogui

if errorlevel 1 (
    echo WaterbucketPE exited with code %errorlevel%. Restarting in 3 seconds (Ctrl+C to stop)...
    timeout /t 3 /nobreak >nul
    goto loop
)
echo WaterbucketPE shut down cleanly.
pause
