Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Starting Financial Intelligence Platform (FIP) via Docker" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

docker compose down -v
docker compose build
docker compose up -d

Write-Host ">> Waiting for containers to become healthy..." -ForegroundColor Yellow
Start-Sleep -Seconds 10
docker compose ps

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "Platform endpoints:" -ForegroundColor Green
Write-Host "API Gateway:           http://localhost:8080"
Write-Host "Auth Service:          http://localhost:8081"
Write-Host "Plugin Framework:      http://localhost:8082"
Write-Host "Event Engine:          http://localhost:8083"
Write-Host "AI Service (FastAPI):  http://localhost:8000/docs"
Write-Host "RabbitMQ Management:   http://localhost:15672 (guest/guest)"
Write-Host "Prometheus:            http://localhost:9090"
Write-Host "Grafana Dashboards:    http://localhost:3000 (admin/admin)"
Write-Host "==========================================================" -ForegroundColor Green
