param(
    [switch]$SkipSmokeTest
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env.prod"

$composeArguments = @(
    "--env-file", ".env.prod",
    "-p", "cinema-prod",
    "-f", "compose.yaml",
    "-f", "compose.prod.yaml"
)

function Invoke-ProdCompose {
    param([string[]]$CommandArguments)

    & docker compose @composeArguments @CommandArguments

    if ($LASTEXITCODE -ne 0) {
        throw "Production Compose command failed."
    }
}

function Wait-ProductionGateway {
    $deadline = [DateTime]::UtcNow.AddSeconds(300)
    $successfulChecks = 0

    do {
        $ready = $true

        foreach ($path in @("/api/movies", "/api/halls", "/api/screenings")) {
            try {
                $response = Invoke-WebRequest `
                    -Uri "http://localhost:8080$path" `
                    -UseBasicParsing `
                    -TimeoutSec 10

                if ($response.StatusCode -ne 200) {
                    $ready = $false
                }
            }
            catch {
                $ready = $false
            }
        }

        if ($ready) {
            $successfulChecks++
        }
        else {
            $successfulChecks = 0
        }

        if ($successfulChecks -ge 3) {
            return
        }

        Start-Sleep -Seconds 5
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "Production Gateway readiness check timed out."
}

Push-Location $projectRoot
$previousPassword = $env:CINEMA_ADMIN_PASSWORD

try {
    if (-not (Test-Path -LiteralPath $envFile)) {
        throw ".env.prod is missing. Run scripts/init-prod.ps1 first."
    }

    # Lozinku citamo bez ispisivanja.
    $passwordLine = Get-Content -LiteralPath $envFile |
        Where-Object { $_ -match "^CINEMA_ADMIN_PASSWORD=" } |
        Select-Object -First 1

    if ([string]::IsNullOrWhiteSpace($passwordLine)) {
        throw "CINEMA_ADMIN_PASSWORD is missing from .env.prod."
    }

    $env:CINEMA_ADMIN_PASSWORD = $passwordLine.Substring(
        "CINEMA_ADMIN_PASSWORD=".Length
    )

    if ([string]::IsNullOrWhiteSpace($env:CINEMA_ADMIN_PASSWORD)) {
        throw "Production admin password must not be empty."
    }

    Write-Host "Checking production configuration..."
    Invoke-ProdCompose -CommandArguments @("config", "--quiet")

    Write-Host "Checking that application images are available..."

    $images = @(
        "cinema/config-server:local",
        "cinema/discovery-server:local",
        "cinema/movie-service:local",
        "cinema/screening-service:local",
        "cinema/seat-inventory-service:local",
        "cinema/reservation-service:local",
        "cinema/payment-service:local",
        "cinema/api-gateway:local"
    )

    foreach ($image in $images) {
        & docker image inspect $image --format "{{.Id}}" | Out-Null

        if ($LASTEXITCODE -ne 0) {
            throw "Missing image: $image. Run the development pipeline first."
        }
    }

    Write-Host "Stopping development containers..."
    & docker compose -p cinema -f compose.yaml stop

    if ($LASTEXITCODE -ne 0) {
        throw "Could not stop development containers."
    }

    Write-Host "Starting isolated production environment..."
    Invoke-ProdCompose -CommandArguments @("up", "-d", "--no-build")

    Write-Host "Waiting for production services..."
    Wait-ProductionGateway

    if (-not $SkipSmokeTest) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $PSScriptRoot "smoke-test.ps1")

        if ($LASTEXITCODE -ne 0) {
            throw "Production smoke test failed."
        }
    }

    Write-Host ""
    Write-Host "PRODUCTION DEPLOY PASSED" -ForegroundColor Green
}
catch {
    Write-Host ""
    Write-Host "PRODUCTION DEPLOY FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
}
finally {
    $env:CINEMA_ADMIN_PASSWORD = $previousPassword
    Pop-Location
}