param(
    [ValidateSet("Build", "Test", "Deploy", "Verify", "All", "Stop")]
    [string]$Action = "All"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot

$services = @(
    "config-server",
    "discovery-server",
    "movie-service",
    "screening-service",
    "seat-inventory-service",
    "reservation-service",
    "payment-service",
    "api-gateway"
)

function Invoke-MavenPhase {
    param([string[]]$MavenArguments)

    foreach ($service in $services) {
        Write-Host ""
        Write-Host "=== $service ===" -ForegroundColor Cyan

        Push-Location (Join-Path $projectRoot $service)
        try {
            & ".\mvnw.cmd" @MavenArguments

            if ($LASTEXITCODE -ne 0) {
                throw "Maven failed for $service."
            }
        }
        finally {
            Pop-Location
        }
    }
}

function Invoke-Compose {
    param([string[]]$ComposeArguments)

    & docker compose @ComposeArguments

    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose failed: $($ComposeArguments -join ' ')"
    }
}

function Wait-Gateway {
    $deadline = [DateTime]::UtcNow.AddSeconds(240)
    $consecutiveSuccesses = 0

    $paths = @(
        "/api/movies",
        "/api/halls",
        "/api/screenings"
    )

    Write-Host "Waiting for Gateway and catalog services..."

    do {
        $allReady = $true

        foreach ($path in $paths) {
            try {
                $response = Invoke-WebRequest `
                    -Uri "http://localhost:8080$path" `
                    -UseBasicParsing `
                    -TimeoutSec 10

                if ($response.StatusCode -ne 200) {
                    $allReady = $false
                }
            }
            catch {
                $allReady = $false
            }
        }

        if ($allReady) {
            $consecutiveSuccesses++
        }
        else {
            $consecutiveSuccesses = 0
        }

        if ($consecutiveSuccesses -ge 3) {
            Write-Host "Gateway and catalog routes are responding."
            return
        }

        Start-Sleep -Seconds 5
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "Gateway/catalog readiness check timed out."
}

function Invoke-SmokeTest {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $PSScriptRoot "smoke-test.ps1")

    if ($LASTEXITCODE -ne 0) {
        throw "Smoke test failed."
    }
}

Push-Location $projectRoot

try {
    switch ($Action) {
        "Build" {
            # clean verify kompajlira, testira i pravi JAR fajlove.
            Invoke-MavenPhase -MavenArguments @("clean", "verify")
        }

        "Test" {
            Invoke-MavenPhase -MavenArguments @("verify")
        }

        "Deploy" {
            Invoke-Compose -ComposeArguments @("config", "--quiet")
            Invoke-Compose -ComposeArguments @("build")
            Invoke-Compose -ComposeArguments @("up", "-d")
            Wait-Gateway
            Invoke-SmokeTest
        }

        "Verify" {
            Wait-Gateway
            Invoke-SmokeTest
        }

        "All" {
            Invoke-MavenPhase -MavenArguments @("clean", "verify")
            Invoke-Compose -ComposeArguments @("config", "--quiet")
            Invoke-Compose -ComposeArguments @("build")
            Invoke-Compose -ComposeArguments @("up", "-d")
            Wait-Gateway
            Invoke-SmokeTest
        }

        "Stop" {
            Invoke-Compose -ComposeArguments @("stop")
        }
    }

    Write-Host ""
    Write-Host "PIPELINE PASSED: $Action" -ForegroundColor Green
    exit 0
}
catch {
    Write-Host ""
    Write-Host "PIPELINE FAILED: $Action" -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
}
finally {
    Pop-Location
}