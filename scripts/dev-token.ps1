<#
.SYNOPSIS
    Logs in to Asgardeo in a browser and returns an access token for calling the API.

.DESCRIPTION
    There is no frontend yet, and the backend is a pure resource server: it validates tokens
    but never issues them. This script stands in for the login screen until the React app
    exists.

    It runs the same Authorization Code + PKCE flow the SPA will use - no client secret,
    because a public client has none. It opens the Asgardeo login, listens on the redirect
    port to catch the authorization code, exchanges it for tokens, and prints the access
    token.

.PARAMETER Call
    Also call GET /api/me with the token and show the response.

.EXAMPLE
    .\scripts\dev-token.ps1
    Log in and print a token.

.EXAMPLE
    $t = .\scripts\dev-token.ps1 -Quiet
    Invoke-RestMethod http://localhost:8080/api/admin/users -Headers @{Authorization="Bearer $t"}
#>
[CmdletBinding()]
param(
    [switch]$Call,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$tenant      = 'pasindudilshan'
$clientId    = '4fnWPKRrxnUyy4wxULpkw4nOhnoa'
$baseUrl     = "https://api.asgardeo.io/t/$tenant"
$redirectUri = 'http://localhost:5173'
$redirectPort = 5173
$apiBase     = 'http://localhost:8080'

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# --- PKCE -----------------------------------------------------------------------------
# The verifier stays here; only its SHA-256 hash travels to Asgardeo. That is what makes a
# public client safe without a secret: an intercepted authorization code is useless without
# the verifier, which never left this process.
$verifierBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($verifierBytes)
$codeVerifier = ConvertTo-Base64Url $verifierBytes

$sha256 = [System.Security.Cryptography.SHA256]::Create()
$challengeBytes = $sha256.ComputeHash([System.Text.Encoding]::ASCII.GetBytes($codeVerifier))
$codeChallenge = ConvertTo-Base64Url $challengeBytes

$stateBytes = New-Object byte[] 16
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($stateBytes)
$state = ConvertTo-Base64Url $stateBytes

# --- Authorization request ------------------------------------------------------------
$scope = [uri]::EscapeDataString('openid profile roles')
$authUrl = "$baseUrl/oauth2/authorize" +
           "?response_type=code" +
           "&client_id=$clientId" +
           "&redirect_uri=$([uri]::EscapeDataString($redirectUri))" +
           "&scope=$scope" +
           "&state=$state" +
           "&code_challenge=$codeChallenge" +
           "&code_challenge_method=S256"

# A plain TcpListener rather than HttpListener: HttpListener needs a URL reservation and
# therefore administrator rights, which this account does not have.
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $redirectPort)
try {
    $listener.Start()
} catch {
    throw "Could not listen on port $redirectPort. Is the Vite dev server or another copy of this script already running? ($($_.Exception.Message))"
}

if (-not $Quiet) {
    Write-Host ''
    Write-Host 'Opening Asgardeo login in your browser.' -ForegroundColor Cyan
    Write-Host 'If it does not open, paste this URL yourself:' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host $authUrl -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'Waiting for the redirect...' -ForegroundColor DarkGray
}

Start-Process $authUrl | Out-Null

# --- Catch the redirect ----------------------------------------------------------------
$client = $listener.AcceptTcpClient()
$stream = $client.GetStream()
$reader = New-Object System.IO.StreamReader($stream)
$requestLine = $reader.ReadLine()

$body = @'
<!doctype html><meta charset="utf-8"><title>Altrium</title>
<body style="font-family:system-ui;padding:3rem;max-width:32rem">
<h2>Signed in</h2><p>Your token is in the terminal. You can close this tab.</p></body>
'@
$writer = New-Object System.IO.StreamWriter($stream)
$writer.WriteLine("HTTP/1.1 200 OK")
$writer.WriteLine("Content-Type: text/html; charset=utf-8")
$writer.WriteLine("Content-Length: $([System.Text.Encoding]::UTF8.GetByteCount($body))")
$writer.WriteLine("Connection: close")
$writer.WriteLine()
$writer.Write($body)
$writer.Flush()
$client.Close()
$listener.Stop()

if ($requestLine -notmatch '^GET\s+(\S+)') {
    throw "Unexpected request on the redirect port: $requestLine"
}
$query = $matches[1]

if ($query -match 'error=([^&\s]+)') {
    $desc = ''
    if ($query -match 'error_description=([^&\s]+)') { $desc = [uri]::UnescapeDataString($matches[1]).Replace('+', ' ') }
    throw "Asgardeo refused the login: $([uri]::UnescapeDataString($matches[1])) $desc"
}
if ($query -notmatch 'code=([^&\s]+)') {
    throw "No authorization code in the redirect: $query"
}
$code = [uri]::UnescapeDataString($matches[1])

# Guards against a code injected from another session being swapped in.
if ($query -match 'state=([^&\s]+)') {
    $returnedState = [uri]::UnescapeDataString($matches[1])
    if ($returnedState -ne $state) {
        throw "State mismatch - discarding this response."
    }
}

# --- Exchange the code ------------------------------------------------------------------
$tokenResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/oauth2/token" -Body @{
    grant_type    = 'authorization_code'
    code          = $code
    redirect_uri  = $redirectUri
    client_id     = $clientId
    code_verifier = $codeVerifier
} -ContentType 'application/x-www-form-urlencoded'

$accessToken = $tokenResponse.access_token

if ($Quiet) {
    return $accessToken
}

# Decode the payload locally just to show who signed in. This is display only - the backend
# verifies the signature properly, and an unverified payload proves nothing.
$payloadSegment = $accessToken.Split('.')[1].Replace('-', '+').Replace('_', '/')
switch ($payloadSegment.Length % 4) { 2 { $payloadSegment += '==' } 3 { $payloadSegment += '=' } }
$payload = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payloadSegment)) | ConvertFrom-Json

Write-Host ''
Write-Host 'Signed in.' -ForegroundColor Green
Write-Host "  subject : $($payload.sub)"
if ($payload.username) { Write-Host "  username: $($payload.username)" }
Write-Host "  expires : $([DateTimeOffset]::FromUnixTimeSeconds($payload.exp).LocalDateTime)"
Write-Host ''
Write-Host 'Access token:' -ForegroundColor Cyan
Write-Host $accessToken
Write-Host ''
Write-Host 'Use it like this:' -ForegroundColor DarkGray
Write-Host "  `$t = .\scripts\dev-token.ps1 -Quiet" -ForegroundColor DarkGray
Write-Host "  Invoke-RestMethod $apiBase/api/me -Headers @{Authorization=`"Bearer `$t`"}" -ForegroundColor DarkGray

if ($Call) {
    Write-Host ''
    Write-Host "GET $apiBase/api/me" -ForegroundColor Cyan
    try {
        Invoke-RestMethod "$apiBase/api/me" -Headers @{ Authorization = "Bearer $accessToken" } | Format-List
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -eq 403) {
            Write-Host "403 - authenticated by Asgardeo, but this subject has no app_user row." -ForegroundColor Yellow
            Write-Host "     Seed the organisation, or check the sub above matches one in docs/seed-data.md." -ForegroundColor Yellow
        } elseif ($status -eq 401) {
            Write-Host "401 - the backend rejected the token. Is ASGARDEO_ISSUER_URI pointing at this tenant?" -ForegroundColor Yellow
        } else {
            Write-Host "Request failed: $($_.Exception.Message)" -ForegroundColor Red
        }
    }
}
