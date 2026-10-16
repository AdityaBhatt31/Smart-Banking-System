#!/usr/bin/env bash
# Smart Banking v3 - build and run (Linux / macOS).
# Needs JDK 17+ and a running MySQL 8 server. Edit config.properties (db.password) first.
set -e
cd "$(dirname "$0")"

command -v javac >/dev/null 2>&1 || { echo "[ERROR] javac not found. Install JDK 17 or newer."; exit 1; }

mkdir -p lib
JAR="lib/mysql-connector-j-8.3.0.jar"
if [ ! -f "$JAR" ]; then
  echo "Downloading MySQL Connector/J 8.3.0 ..."
  if ! curl -fsSL -o "$JAR" "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar"; then
    rm -f "$JAR"
    echo "[ERROR] Download failed. Put mysql-connector-j-8.3.0.jar into the lib folder yourself."
    exit 1
  fi
fi

echo "Compiling..."
rm -rf out && mkdir -p out
find src -name '*.java' > sources.txt
javac -encoding UTF-8 -d out -cp "lib/*" @sources.txt
rm -f sources.txt

echo "Starting Smart Banking on http://localhost:8080  (Ctrl+C to stop)"
exec java -cp "out:lib/*" com.banking.Main "$@"
