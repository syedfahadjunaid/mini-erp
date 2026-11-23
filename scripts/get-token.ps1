<#
.SYNOPSIS
    Prints an access token for a demo user, e.g. to paste into Swagger UI's "Authorize" dialog
    (http://localhost:8780/swagger-ui.html). Tokens are valid for 5 minutes.

.EXAMPLE
    .\scripts\get-token.ps1 walt
    .\scripts\get-token.ps1 walt | Set-Clipboard
#>
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('adam', 'pam', 'max', 'bea', 'walt', 'sue', 'finn')][string]$User)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
Get-ErpToken $User
