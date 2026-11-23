<#
.SYNOPSIS
    Creates .env with a fresh random value for every secret listed in .env.example, or adds the
    secrets a newer .env.example introduced to an existing .env.

.DESCRIPTION
    Run once before the first "docker compose up", and again after pulling changes that add a
    secret. Existing values are never changed: Postgres and Keycloak keep the passwords they were
    first started with (to start over: "docker compose down -v", delete .env, run this again).

    The values are never printed. The demo users' password is DEMO_USER_PASSWORD in .env.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
$example = Join-Path $root '.env.example'
# UTF-8 without a BOM: docker compose would read a BOM as part of the first variable name.
$utf8 = New-Object System.Text.UTF8Encoding($false)

function New-Secret {
    # 24 random bytes from the OS's cryptographic generator, as URL-safe text (no quoting issues).
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes)).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

if (-not (Test-Path $envFile)) {
    $lines = foreach ($line in Get-Content $example) {
        if ($line -match '^([A-Z0-9_]+)=$') { "$($Matches[1])=$(New-Secret)" } else { $line }
    }
    [System.IO.File]::WriteAllLines($envFile, [string[]]$lines, $utf8)
    Write-Host "Wrote $envFile with new random secrets."
    return
}

# .env exists: only add what's missing.
$present = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^([A-Z0-9_]+)=') { $present[$Matches[1]] = $true }
}
$added = @()
$newLines = @()
foreach ($line in Get-Content $example) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$' -and -not $present.ContainsKey($Matches[1])) {
        # Empty in the example = a secret to generate; otherwise a default to copy as it is.
        if ($Matches[2] -eq '') { $newLines += "$($Matches[1])=$(New-Secret)" } else { $newLines += $line }
        $added += $Matches[1]
    }
}
if ($added.Count -eq 0) {
    Write-Host ".env already has every setting; leaving it alone."
    return
}
$existing = [System.IO.File]::ReadAllText($envFile, $utf8)
if ($existing.Length -gt 0 -and -not $existing.EndsWith("`n")) { $existing += "`n" }
[System.IO.File]::WriteAllText($envFile, $existing + (($newLines -join "`n") + "`n"), $utf8)
Write-Host "Added to .env: $($added -join ', ')"
