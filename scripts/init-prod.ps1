$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $projectRoot ".env.prod"

if (Test-Path -LiteralPath $destination) {
    throw ".env.prod already exists. Existing passwords were not changed."
}

$generator = [Security.Cryptography.RandomNumberGenerator]::Create()

try {
    $keys = @(
        "MOVIE_DB_PASSWORD",
        "SCREENING_DB_PASSWORD",
        "INVENTORY_DB_PASSWORD",
        "RESERVATION_DB_PASSWORD",
        "PAYMENT_DB_PASSWORD",
        "RABBITMQ_PASSWORD",
        "CINEMA_ADMIN_PASSWORD"
    )

    $lines = foreach ($key in $keys) {
        $bytes = New-Object byte[] 24
        $generator.GetBytes($bytes)
        $value = [BitConverter]::ToString($bytes).Replace("-", "")
        "$key=$value"
    }

    Set-Content -LiteralPath $destination -Value $lines -Encoding ASCII
    Write-Host ".env.prod created. Keep it private and preserve it."
}
finally {
    $generator.Dispose()
}