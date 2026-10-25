#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "Building Financial Intelligence Platform (FIP)"
echo "=========================================================="

echo ">> 1. Building Java Microservices..."
gradle build -x test --no-daemon

echo ">> 2. Validating Python AI Service Dependencies..."
cd ai-service
if command -v python3 &>/dev/null; then
    python3 -m pip install -r requirements.txt
elif command -v python &>/dev/null; then
    python -m pip install -r requirements.txt
fi
cd ..

echo ">> Build completed successfully!"
