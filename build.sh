#!/bin/bash
# Builds goblin.jar without Maven - the project has no external dependencies.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build
javac -d build $(find src -name "*.java")
jar --create --file goblin.jar --main-class space.perrys.goblin.Goblin -C build .
echo "goblin.jar built. Run: java -jar goblin.jar --help"
