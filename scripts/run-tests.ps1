Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Running All Microservices Unit & Integration Test Suites" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

Write-Host ">> 1. Running Java Tests with Gradle..." -ForegroundColor Yellow
if (Test-Path ".\gradlew.bat") {
    .\gradlew.bat test --no-daemon
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle test --no-daemon
} else {
    Write-Warning "Gradle CLI not found locally. Run tests via Docker container or inside IDE."
}

Write-Host ">> 2. Running Python AI Pytest Suite..." -ForegroundColor Yellow
if (Get-Command pytest -ErrorAction SilentlyContinue) {
    Push-Location ai-service
    pytest -v tests/
    Pop-Location
} else {
    Write-Warning "Pytest not found locally. You can execute tests inside container: docker compose exec ai-service pytest"
}

Write-Host ">> Test script completed!" -ForegroundColor Green
