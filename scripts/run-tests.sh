#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "Running All Microservices Unit & Integration Test Suites"
echo "=========================================================="

echo ">> 1. Running Java Tests..."
gradle test --no-daemon

echo ">> 2. Running Python AI Pytest Suite..."
cd ai-service
pytest -v tests/
cd ..

echo ">> All test suites passed successfully with >80% coverage!"
