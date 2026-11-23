<#
.SYNOPSIS
    Helpers for the demo scripts. Dot-source it:  . .\scripts\lib.ps1

.DESCRIPTION
    Get-ErpToken      a demo user's access token (dev-only "erp-cli" client, password grant)
    Invoke-Erp        calls the API (http://localhost:8780) as a demo user; throws on errors
    Invoke-ErpRaw     the same, but returns @{ Status; Body } instead of throwing (to show errors)
    Save-ErpFile      downloads an export (PDF, Excel) to a file

    Written for Windows PowerShell 5.1. The demo password is read from .env and never printed.
#>

$script:ErpRoot = Split-Path -Parent $PSScriptRoot
$script:ApiBase = 'http://localhost:8780'
$script:TokenUrl = 'http://localhost:8880/realms/erp/protocol/openid-connect/token'
$script:TokenCache = @{}

function Get-DemoPassword {
    $envFile = Join-Path $script:ErpRoot '.env'
    if (-not (Test-Path $envFile)) { throw ".env not found: run .\scripts\init-env.ps1 first" }
    $line = Get-Content $envFile | Where-Object { $_ -match '^DEMO_USER_PASSWORD=' } | Select-Object -First 1
    if (-not $line) { throw "DEMO_USER_PASSWORD missing from .env" }
    return $line.Substring('DEMO_USER_PASSWORD='.Length)
}

function Get-ErpToken {
    param([Parameter(Mandatory)][ValidateSet('adam', 'pam', 'max', 'bea', 'walt', 'sue', 'finn')][string]$User)
    $cached = $script:TokenCache[$User]
    if ($cached -and $cached.Expires -gt (Get-Date)) { return $cached.Token }
    $response = Invoke-RestMethod -Method Post -Uri $script:TokenUrl -Body @{
        grant_type = 'password'
        client_id  = 'erp-cli'
        username   = $User
        password   = (Get-DemoPassword)
        scope      = 'openid'
    }
    # Tokens live 5 minutes; refresh a little early.
    $script:TokenCache[$User] = @{ Token = $response.access_token; Expires = (Get-Date).AddSeconds(240) }
    return $response.access_token
}

function New-ErpRequest {
    param([string]$User, [string]$Path, [string]$Method, $Body)
    $params = @{
        Method  = $Method
        Uri     = "$script:ApiBase$Path"
        Headers = @{ Authorization = "Bearer $(Get-ErpToken $User)" }
    }
    if ($Method -ne 'GET') {
        # PowerShell 5.1 sends a form content type when a POST has no body, so always send JSON.
        if ($null -eq $Body) { $Body = @{} }
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))
        $params.ContentType = 'application/json; charset=utf-8'
    }
    return $params
}

function Invoke-Erp {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET', 'POST', 'PUT')][string]$Method = 'GET',
        $Body
    )
    $params = New-ErpRequest -User $User -Path $Path -Method $Method -Body $Body
    # PowerShell 5.1 passes a JSON array down the pipeline as ONE object; ForEach-Object
    # unrolls it, so callers can filter the items (and wrap the result in @() for a list).
    return Invoke-RestMethod @params | ForEach-Object { $_ }
}

function Invoke-ErpRaw {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET', 'POST', 'PUT')][string]$Method = 'GET',
        $Body
    )
    $params = New-ErpRequest -User $User -Path $Path -Method $Method -Body $Body
    try {
        $response = Invoke-WebRequest @params -UseBasicParsing
        return @{ Status = [int]$response.StatusCode; Body = ($response.Content | ConvertFrom-Json) }
    } catch {
        # PowerShell 7 (HttpResponseException) puts the error body in ErrorDetails; Windows
        # PowerShell 5.1 (WebException) leaves that empty, but its response stream can be rewound.
        $errorResponse = $_.Exception.Response
        if ($null -eq $errorResponse) { throw }
        $text = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $text = $_.ErrorDetails.Message
        } elseif ($errorResponse -is [System.Net.HttpWebResponse]) {
            $stream = $errorResponse.GetResponseStream()
            if ($stream.CanSeek) { $stream.Position = 0 }
            $text = (New-Object System.IO.StreamReader($stream)).ReadToEnd()
        }
        $body = $null
        if ($text) { $body = $text | ConvertFrom-Json }
        return @{ Status = [int]$errorResponse.StatusCode; Body = $body }
    }
}

function Save-ErpFile {
    param(
        [Parameter(Mandatory)][string]$User,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$OutFile
    )
    $directory = Split-Path -Parent $OutFile
    if (-not (Test-Path $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
    Invoke-WebRequest -Uri "$script:ApiBase$Path" -Headers @{ Authorization = "Bearer $(Get-ErpToken $User)" } `
        -OutFile $OutFile -UseBasicParsing
    return Get-Item $OutFile
}

function Wait-Until {
    param([Parameter(Mandatory)][scriptblock]$Condition, [int]$TimeoutSeconds = 30, [string]$What = 'condition')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Milliseconds 300
    }
    throw "Timed out after $TimeoutSeconds s waiting for $What"
}
