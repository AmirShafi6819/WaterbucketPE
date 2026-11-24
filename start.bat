@echo off
setlocal
rem WaterBucketPE start script (Windows)
rem - finds the proxy jar automatically: exact Waterbucket.jar first, then the
rem   newest Waterbucket*.jar (so Waterbucket-1.5.0.jar straight from a release
rem   works without renaming), then any single *.jar in this folder
rem - restarts the proxy automatically after a crash; closing it stops cleanly

cd /d "%~dp0"

rem ---------- locate the jar ----------
set "JAR_NAME="
if exist "Waterbucket.jar" set "JAR_NAME=Waterbucket.jar"
if not defined JAR_NAME for %%f in ("Waterbucket*.jar") do set "JAR_NAME=%%f"
if not defined JAR_NAME for %%f in ("*.jar") do set "JAR_NAME=%%f"
if not defined JAR_NAME (
    echo WaterBucketPE: no proxy jar found in this folder.
    echo Put Waterbucket.jar or Waterbucket-1.5.0.jar next to start.bat and run it again.
    pause
    exit /b 1
)

rem ---------- locate Java 17+ ----------
set "JAVA_BIN=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
"%JAVA_BIN%" -version >nul 2>&1
if errorlevel 1 (
    echo WaterBucketPE requires Java 17 or newer.
    echo Install it and either put java on PATH or set JAVA_HOME.
    pause
    exit /b 1
)
rem reject old JVMs: "1.x", "9.x" and "10.x".."16.x" - everything 17+ passes
"%JAVA_BIN%" -version 2>&1 | findstr /r /c:"\"1\.[0-8]" /c:"\"9\." /c:"\"1[0-6]\." >nul
if not errorlevel 1 (
    echo WaterBucketPE requires Java 17 or newer. Set JAVA_HOME to a Java 17+ installation.
    pause
    exit /b 1
)

rem ---------- run ----------
set "HEAP=1G"
if defined WATERBUCKET_MEMORY set "HEAP=%WATERBUCKET_MEMORY%"

:loop
set /a "T1=((1%TIME:~0,2%-100)*360000)+((1%TIME:~3,2%-100)*6000)+((1%TIME:~6,2%-100)*100)+(1%TIME:~9,2%-100)"
"%JAVA_BIN%" -Xms%HEAP% -Xmx%HEAP% ^
     -XX:+UseG1GC ^
     -XX:+UnlockExperimentalVMOptions ^
     -XX:MaxGCPauseMillis=120
     -XX:InitiatingHeapOccupancyPercent=35 ^
     -XX:G1NewSizePercent=30 ^
     -XX:G1MaxNewSizePercent=40 ^
     -XX:G1ReservePercent=20 ^
     -XX:G1HeapRegionSize=8M ^
     -XX:+ParallelRefProcEnabled ^
     -XX:+UseStringDeduplication ^
     -jar "%JAR_NAME%" %*
set /a "RC=%errorlevel%"
set /a "T2=((1%TIME:~0,2%-100)*360000)+((1%TIME:~3,2%-100)*6000)+((1%TIME:~6,2%-100)*100)+(1%TIME:~9,2%-100)"
set /a "RAN=%T2%-%T1%"
if %RAN% lss 0 set /a "RAN+=8640000"
if %RC% equ 0 (
    echo WaterBucketPE shut down cleanly.
    pause
    endlocal
    exit /b 0
)
rem An instant exit is a launch failure (bad JVM options, occupied port, broken
rem config) - restarting cannot fix it, so stop instead of looping on error spam.
if %RAN% lss 5000 (
    echo WaterBucketPE failed to start ^(code %RC%^). Not restarting.
    echo Fix the error above and run start.bat again.
    pause
    endlocal
    exit /b 1
)
echo WaterBucketPE exited with code %RC%. Restarting in 3 seconds. Close this window to stop.
timeout /t 3 /nobreak >nul
goto loop
