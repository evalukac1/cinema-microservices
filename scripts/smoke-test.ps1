param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin",
    [string]$Password = $env:CINEMA_ADMIN_PASSWORD
)

$ErrorActionPreference = "Stop"

# Podrazumevana lozinka namenjena je samo lokalnom razvoju.
if ([string]::IsNullOrWhiteSpace($Password)) {
    $Password = "cinema_local_admin_password"
}

$BaseUrl = $BaseUrl.TrimEnd("/")
$encodedCredentials = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes("${Username}:${Password}")
)

$headers = @{
    Authorization = "Basic $encodedCredentials"
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )

    $arguments = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $headers
        TimeoutSec = 30
    }

    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }

    Invoke-RestMethod @arguments
}

function Assert-Equal {
    param(
        [object]$Actual,
        [object]$Expected,
        [string]$Description
    )

    if ($Actual -ne $Expected) {
        throw "$Description : expected '$Expected', received '$Actual'."
    }
}

function Wait-Reservation {
    param(
        [string]$Id,
        [string]$ExpectedStatus
    )

    $deadline = [DateTime]::UtcNow.AddSeconds(90)

    do {
        $reservation = Invoke-Api GET "/api/reservations/$Id"

        if ($reservation.status -eq $ExpectedStatus) {
            return $reservation
        }

        if ($reservation.status -in @(
            "CONFIRMED", "CANCELLED", "EXPIRED", "REJECTED", "REFUNDED"
        )) {
            throw "Unexpected reservation status: $($reservation.status)"
        }

        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "Timeout: reservation $Id did not reach $ExpectedStatus."
}

try {
    $runId = [Guid]::NewGuid().ToString("N").Substring(0, 12)

    Write-Host "1/5 Creating movie, hall and future screening..."

    $movie = Invoke-Api POST "/api/movies" @{
        title = "Smoke test $runId"
        genre = "DRAMA"
        durationMinutes = 100
    }

    $hall = Invoke-Api POST "/api/halls" @{
        name = "Smoke hall $runId"
        rowCount = 2
        seatsPerRow = 4
    }

    $screening = Invoke-Api POST "/api/screenings" @{
        movieId = $movie.id
        hallId = $hall.id
        startsAt = [DateTimeOffset]::UtcNow.AddDays(2).ToString("o")
        ticketPrice = 8.50
        currency = "EUR"
    }

    Write-Host "2/5 Creating reservation and checking repeated request..."

    $successId = [Guid]::NewGuid().ToString()
    $request = @{
        reservationId = $successId
        screeningId = $screening.id
        customerEmail = "smoke@example.com"
        seats = @(
            @{ rowNumber = 1; seatNumber = 1 }
            @{ rowNumber = 1; seatNumber = 2 }
        )
    }

    $created = Invoke-Api POST "/api/reservations" $request
    Assert-Equal $created.status "AWAITING_PAYMENT" "Initial status"
    Assert-Equal ([decimal]$created.totalPrice) ([decimal]17) "Total price"

    $repeated = Invoke-Api POST "/api/reservations" $request
    Assert-Equal $repeated.id $created.id "Repeated reservation ID"
    Assert-Equal $repeated.expiresAt $created.expiresAt "Hold expiration"

    Write-Host "3/5 Checking successful payment and seat confirmation..."

    $null = Invoke-Api POST "/api/reservations/$successId/pay" @{
        simulateFailure = $false
    }

    $null = Wait-Reservation $successId "CONFIRMED"

    $payment = Invoke-Api GET "/api/payments/reservation/$successId"
    Assert-Equal $payment.status "SUCCEEDED" "Successful payment"

    $hold = Invoke-Api GET "/api/inventory/holds/$successId"
    Assert-Equal $hold.status "CONFIRMED" "Confirmed hold"

    Write-Host "4/5 Checking declined payment and released hold..."

    $failedId = [Guid]::NewGuid().ToString()

    $failedReservation = Invoke-Api POST "/api/reservations" @{
        reservationId = $failedId
        screeningId = $screening.id
        customerEmail = "smoke@example.com"
        seats = @(
            @{ rowNumber = 1; seatNumber = 3 }
            @{ rowNumber = 1; seatNumber = 4 }
        )
    }

    Assert-Equal $failedReservation.status "AWAITING_PAYMENT" "Initial status"

    $null = Invoke-Api POST "/api/reservations/$failedId/pay" @{
        simulateFailure = $true
    }

    $null = Wait-Reservation $failedId "CANCELLED"

    $failedPayment = Invoke-Api GET "/api/payments/reservation/$failedId"
    Assert-Equal $failedPayment.status "FAILED" "Declined payment"
    Assert-Equal $failedPayment.failureReason "SIMULATED_DECLINE" "Decline reason"

    $releasedHold = Invoke-Api GET "/api/inventory/holds/$failedId"
    Assert-Equal $releasedHold.status "RELEASED" "Released hold"

    Write-Host "5/5 Checking that released seats can be reserved again..."

    $reuseId = [Guid]::NewGuid().ToString()

    $reused = Invoke-Api POST "/api/reservations" @{
        reservationId = $reuseId
        screeningId = $screening.id
        customerEmail = "smoke@example.com"
        seats = @(
            @{ rowNumber = 1; seatNumber = 3 }
            @{ rowNumber = 1; seatNumber = 4 }
        )
    }

    Assert-Equal $reused.status "AWAITING_PAYMENT" "Reused seats"

    $cancelled = Invoke-Api POST "/api/reservations/$reuseId/cancel"
    Assert-Equal $cancelled.status "CANCELLED" "Test reservation cleanup"

    Write-Host ""
    Write-Host "SMOKE TEST PASSED" -ForegroundColor Green
    Write-Host "Screening ID: $($screening.id)"
    Write-Host "Confirmed reservation: $successId"
    Write-Host "Declined reservation: $failedId"
    exit 0
}
catch {
    Write-Host ""
    Write-Host "SMOKE TEST FAILED" -ForegroundColor Red
    Write-Host $_.Exception.Message

    if ($_.ErrorDetails.Message) {
        Write-Host $_.ErrorDetails.Message
    }

    exit 1
}