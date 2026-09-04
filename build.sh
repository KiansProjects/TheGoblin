#!/bin/bash
# Baut goblin.jar ohne Maven - das Projekt hat keine externen Dependencies.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build
javac -d build $(find src -name "*.java")
jar --create --file goblin.jar --main-class space.perrys.goblin.Goblin -C build .
echo "goblin.jar gebaut. Start: java -jar goblin.jar --help"
