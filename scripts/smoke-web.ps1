<#
.SYNOPSIS
    Signs in to the web app the way a browser does, and checks the BFF's security rules.

.DESCRIPTION
    Against the running stack (http://localhost:5780), step by step like a browser:
      1. /api/me without a session is 401 (not a redirect);
      2. sign-in: /oauth2/authorization/keycloak -> Keycloak's login form -> submit the demo user's
         credentials -> back to /login/oauth2/code/keycloak -> session cookie ERP_SESSION (HttpOnly)
         and the readable CSRF cookie ERP-XSRF-TOKEN;
      3. /api/me with the session is 200, and no token ever reached this "browser";
      4. a POST without the CSRF header is 403; with it, the request reaches the API;
      5. sign-out ends the app session AND the Keycloak session: the next sign-in shows the
         login form again instead of silently succeeding.

    Keycloak marks its cookies Secure. Browsers still send those to http://localhost (a "secure
    context"), but .NET doesn't, so this script carries Keycloak's cookies itself.

    The demo password is read from .env and never printed. Written for Windows PowerShell 5.1.
#>
[CmdletBinding()]
param([ValidateSet('adam', 'pam', 'max', 'bea', 'walt', 'sue', 'finn')][string]$User = 'walt')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
$web = 'http://localhost:5780'
$checks = 0
# Keycloak's own cookies, by name. Each request to Keycloak gets a fresh jar holding them as plain
# (not Secure) cookies; what Keycloak sets in its answer is copied back here.
$keycloakCookies = @{}
$keycloakRealm = [Uri]'http://localhost:8880/realms/erp/'

function Pass([string]$text) { $script:checks++; Write-Host "  ok  $text" -ForegroundColor Green }
function Fail([string]$text) { throw "FAILED: $text" }

<# One HTTP request, redirects NOT followed. Returns @{ Status; Location; Content }. #>
function Invoke-Step {
    param([string]$Uri, [string]$Method = 'GET', $Body, $Session, [hashtable]$Headers = @{}, [switch]$KeycloakCookies)
    $params = @{ Uri = $Uri; Method = $Method; UseBasicParsing = $true; MaximumRedirection = 0; ErrorAction = 'SilentlyContinue' }
    if ($Session) { $params.WebSession = $Session }
    if ($KeycloakCookies) {
        $jarForKeycloak = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        foreach ($entry in $script:keycloakCookies.GetEnumerator()) {
            $jarForKeycloak.Cookies.Add($script:keycloakRealm, (New-Object System.Net.Cookie($entry.Key, $entry.Value, '/realms/erp/')))
        }
        $params.WebSession = $jarForKeycloak
    }
    if ($null -ne $Body) { $params.Body = $Body }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    try {
        $response = Invoke-WebRequest @params
    } catch {
        if ($_.Exception.Response) {
            return @{ Status = [int]$_.Exception.Response.StatusCode; Location = $null; Content = $_.ErrorDetails.Message }
        }
        throw
    }
    if ($KeycloakCookies) { Save-KeycloakCookies $response.Headers['Set-Cookie'] }
    return @{ Status = [int]$response.StatusCode; Location = $response.Headers['Location']; Content = $response.Content }
}

<# Remembers the cookies Keycloak set; an empty value means Keycloak deleted the cookie. #>
function Save-KeycloakCookies($setCookie) {
    if (-not $setCookie) { return }
    $text = @($setCookie) -join ', '
    foreach ($match in [regex]::Matches($text, '(?:^|,\s*)(AUTH_SESSION_ID|KC_AUTH_SESSION_HASH|KC_RESTART|KEYCLOAK_IDENTITY|KEYCLOAK_SESSION)=([^;]*)')) {
        $name = $match.Groups[1].Value
        if ($match.Groups[2].Value) { $script:keycloakCookies[$name] = $match.Groups[2].Value } else { $script:keycloakCookies.Remove($name) }
    }
}

<# Starts a sign-in in $Session and returns Keycloak's answer to the authorization request. #>
function Start-SignIn($Session) {
    $start = Invoke-Step "$web/oauth2/authorization/keycloak" -Session $Session
    if ($start.Status -ne 302 -or $start.Location -notlike 'http://localhost:8880/realms/erp/protocol/openid-connect/auth*') {
        Fail "sign-in didn't redirect to Keycloak (HTTP $($start.Status))"
    }
    return Invoke-Step $start.Location -KeycloakCookies
}

$jar = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# 1. No session yet
$anonymous = Invoke-Step "$web/api/me" -Session $jar
if ($anonymous.Status -ne 401) { Fail "/api/me without a session gave $($anonymous.Status), expected 401" }
Pass "/api/me without a session is 401"

# 2. Sign in through Keycloak's real login page
$loginPage = Start-SignIn $jar
if ($loginPage.Content -notmatch '<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"') { Fail "Keycloak's login form not found" }
$action = [System.Net.WebUtility]::HtmlDecode($Matches[1])
Pass "sign-in redirected to Keycloak's login form (code flow with PKCE)"

$submitted = Invoke-Step $action -Method Post -KeycloakCookies -Body @{ username = $User; password = (Get-DemoPassword); credentialId = '' }
if ($submitted.Status -ne 302 -or $submitted.Location -notlike "$web/login/oauth2/code/keycloak?*") {
    Fail "Keycloak didn't accept the login (HTTP $($submitted.Status))"
}
$callback = Invoke-Step $submitted.Location -Session $jar
if ($callback.Status -ne 302 -or $callback.Location -ne '/') { Fail "the API didn't complete the sign-in (HTTP $($callback.Status), to '$($callback.Location)')" }
$spa = Invoke-Step "$web/" -Session $jar
if ($spa.Content -notmatch '<div id="root">') { Fail "the app's page didn't load" }
Pass "Keycloak sent the browser back; the API exchanged the code and redirected to the app"

$session = $jar.Cookies.GetCookies($web) | Where-Object { $_.Name -eq 'ERP_SESSION' }
if (-not $session) { Fail "no ERP_SESSION cookie" }
if (-not $session.HttpOnly) { Fail "ERP_SESSION must be HttpOnly" }
Pass "session cookie ERP_SESSION is set and HttpOnly"

# 3. Signed in
$me = Invoke-RestMethod "$web/api/me" -WebSession $jar
if ($me.username -ne $User) { Fail "/api/me says $($me.username), expected $User" }
Pass "/api/me with the session: $($me.username) ($($me.displayName)), roles $($me.roles -join ', ')"
if ($jar.Cookies.GetCookies($web) | Where-Object { $_.Value -match '^eyJ' }) { Fail "a JWT-looking value reached the app's cookies" }
Pass "no token among the app's cookies"

# 4. CSRF: refused without the header, accepted with it (the body is deliberately empty: 400)
$csrf = ($jar.Cookies.GetCookies($web) | Where-Object { $_.Name -eq 'ERP-XSRF-TOKEN' }).Value
if (-not $csrf) { Fail "no ERP-XSRF-TOKEN cookie after the first API call" }
$json = @{ 'Content-Type' = 'application/json' }
$noHeader = Invoke-Step "$web/api/transfers" -Method Post -Session $jar -Body '{}' -Headers $json.Clone()
if ($noHeader.Status -ne 403) { Fail "POST without the CSRF header gave $($noHeader.Status), expected 403" }
Pass "POST without the CSRF header is refused (403)"
$headers = $json.Clone(); $headers['X-XSRF-TOKEN'] = $csrf
$withHeader = Invoke-Step "$web/api/transfers" -Method Post -Session $jar -Body '{}' -Headers $headers
if ($withHeader.Status -eq 403) { Fail "POST with the CSRF header was still refused" }
Pass "POST with the CSRF header reaches the API (HTTP $($withHeader.Status): validation of the empty body)"

# 5. Sign out of the app and of Keycloak
$logout = Invoke-RestMethod "$web/logout" -Method Post -WebSession $jar -Headers @{ 'X-XSRF-TOKEN' = $csrf } -ContentType 'application/json' -Body '{}'
if ($logout.logoutUrl -notlike 'http://localhost:8880/realms/erp/protocol/openid-connect/logout?*id_token_hint=*') { Fail "unexpected logout URL" }
$after = Invoke-Step "$web/api/me" -Session $jar
if ($after.Status -ne 401) { Fail "/api/me after sign-out gave $($after.Status), expected 401" }
Pass "app session ended: /api/me is 401 again"
$kcLogout = Invoke-Step $logout.logoutUrl -KeycloakCookies
if ($kcLogout.Status -ne 302 -or $kcLogout.Location -ne "$web/") { Fail "Keycloak logout didn't return to the app (HTTP $($kcLogout.Status))" }
$again = Start-SignIn (New-Object Microsoft.PowerShell.Commands.WebRequestSession)  # same Keycloak cookies, new app tab
if ($again.Content -notmatch 'kc-form-login') { Fail "signing in again succeeded without a password: the Keycloak session is still alive" }
Pass "Keycloak session ended too: signing in again asks for the password"

Write-Host ""
Write-Host "Web sign-in smoke test passed ($checks checks) as $User." -ForegroundColor Green
