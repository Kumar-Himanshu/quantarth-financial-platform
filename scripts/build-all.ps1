Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Building Financial Intelligence Platform (FIP)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

Write-Host ">> 1. Building Java Microservices with Gradle..." -ForegroundColor Yellow
if (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle build -x test --no-daemon
} elseif (Test-Path ".\gradlew.bat") {
    .\gradlew.bat build -x test --no-daemon
} else {
    Write-Warning "Gradle CLI not found locally. You can execute builds inside Docker containers via: docker compose build"
}

Write-Host ">> 2. Validating Python AI Service..." -ForegroundColor Yellow
if (Get-Command python -ErrorAction SilentlyContinue) {
    Push-Location ai-service
    python -m pip install -r requirements.txt
    Pop-Location
}

Write-Host ">> Build script finished!" -ForegroundColor Green
