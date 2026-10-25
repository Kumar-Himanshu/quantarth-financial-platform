#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "Starting Financial Intelligence Platform (FIP) via Docker"
echo "=========================================================="

docker compose down -v
docker compose build
docker compose up -d

echo ">> Waiting for containers to become healthy..."
sleep 10
docker compose ps

echo "=========================================================="
echo "Platform endpoints:"
echo "API Gateway:           http://localhost:8080"
echo "Auth Service:          http://localhost:8081"
echo "Plugin Framework:      http://localhost:8082"
echo "Event Engine:          http://localhost:8083"
echo "AI Service (FastAPI):  http://localhost:8000/docs"
echo "RabbitMQ Management:   http://localhost:15672 (guest/guest)"
echo "Prometheus:            http://localhost:9090"
echo "Grafana Dashboards:    http://localhost:3000 (admin/admin)"
echo "=========================================================="
