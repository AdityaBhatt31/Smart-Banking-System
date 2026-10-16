@echo off
REM ============================================================
REM  Smart Banking v3 - build and run (Windows)
REM  Needs: JDK 17+ (java and javac in PATH) and a running MySQL 8 server.
REM  The MySQL driver is downloaded automatically into the lib folder.
REM  Edit config.properties (db.password) before the first run.
REM  Then open http://localhost:8080
REM ============================================================
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  echo [ERROR] javac not found. Install JDK 17 or newer and make sure it is in PATH.
  pause & exit /b 1
)

if not exist lib mkdir lib
set "JAR=lib\mysql-connector-j-8.3.0.jar"
if not exist "%JAR%" (
  echo Downloading MySQL Connector/J 8.3.0 ...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -UseBasicParsing -Uri 'https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar' -OutFile '%JAR%' } catch { exit 1 }"
  if errorlevel 1 (
    echo [ERROR] Download failed. Download mysql-connector-j-8.3.0.jar yourself and put it in the lib folder.
    if exist "%JAR%" del "%JAR%"
    pause & exit /b 1
  )
)

echo Compiling...
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out -cp "lib\*" @sources.txt
set "RC=%errorlevel%"
del sources.txt
if not "%RC%"=="0" (
  echo [ERROR] Compilation failed. See the messages above.
  pause & exit /b 1
)

echo Starting Smart Banking on http://localhost:8080  (Ctrl+C to stop)
java -cp "out;lib\*" com.banking.Main %*
pause
