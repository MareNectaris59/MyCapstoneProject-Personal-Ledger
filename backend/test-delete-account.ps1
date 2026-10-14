# Account deletion end-to-end test.
# NOTE: this file must stay PURE ASCII. Windows PowerShell 5.1 reads .ps1 using
# the system ANSI code page, so Chinese literals get mangled and break string
# terminators. Same discipline as test-api.ps1.
#
# Also note: a bare hashtable passed positionally is treated as a PARAMETER
# COLLECTION, so every payload is wrapped as (,@{ ... }) to bind it to $Body.

$ErrorActionPreference = 'Stop'
$Base = 'http://127.0.0.1:8080'
Add-Type -AssemblyName System.Net.Http
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromSeconds(30)

function Call {
  param([string]$Method, [string]$Path, $Body, [string]$Token)
  $req = New-Object System.Net.Http.HttpRequestMessage(
    [System.Net.Http.HttpMethod]::new($Method), "$Base$Path")
  if ($Token) {
    $req.Headers.Authorization =
      New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
  }
  if ($null -ne $Body) {
    $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 6 }
    $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, 'application/json')
  }
  $resp = $http.SendAsync($req).GetAwaiter().GetResult()
  $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  $status = [int]$resp.StatusCode
  $req.Dispose(); $resp.Dispose()
  $body = $null
  if (-not [string]::IsNullOrWhiteSpace($text)) { try { $body = $text | ConvertFrom-Json } catch {} }
  return @{ http = $status; code = $body.code; message = $body.message; data = $body.data }
}

$stamp = Get-Date -Format 'HHmmss'
$email = "del$stamp@example.com"
$pwd   = 'del123456'

Write-Host ''
Write-Host '=== 1. register ==='
$reg = Call POST '/api/v1/auth/register' (,@{ email = $email; password = $pwd; nickname = 'tobedeleted' })
Write-Host "  code=$($reg.code)  userId=$($reg.data.user.userId)"
$token  = $reg.data.accessToken
$oldUid = $reg.data.user.userId

Write-Host ''
Write-Host '=== 2. before deletion: token works ==='
$me = Call GET '/api/v1/auth/me' $null $token
Write-Host "  GET /auth/me -> code=$($me.code)  nickname=$($me.data.user.nickname)"

Write-Host ''
Write-Host '=== 3. wrong password must be rejected ==='
$bad = Call POST '/api/v1/auth/delete' (,@{ password = 'wrong-password'; reason = 'test' }) $token
Write-Host "  code=$($bad.code)  msg=$($bad.message)"
Write-Host '  (expect 1203)'

Write-Host ''
Write-Host '=== 4. delete with correct password ==='
$del = Call POST '/api/v1/auth/delete' (,@{ password = $pwd; reason = 'api test' }) $token
Write-Host "  code=$($del.code)"
Write-Host "  deletedAt=$($del.data.deletedAt)"
Write-Host "  message=$($del.data.message)"

Write-Host ''
Write-Host '=== 5. after deletion: old token must be revoked ==='
$after = Call GET '/api/v1/auth/me' $null $token
Write-Host "  GET /auth/me -> http=$($after.http) code=$($after.code)   (expect 401 / 1005)"

Write-Host ''
Write-Host '=== 6. after deletion: cannot log in again ==='
$login = Call POST '/api/v1/auth/login' (,@{ email = $email; password = $pwd })
Write-Host "  login -> code=$($login.code) msg=$($login.message)   (expect 1203)"

Write-Host ''
Write-Host '=== 7. same email can register again (unique index released) ==='
$again = Call POST '/api/v1/auth/register' (,@{ email = $email; password = 'new123456'; nickname = 'fresh' })
Write-Host "  code=$($again.code)  new userId=$($again.data.user.userId)"

Write-Host ''
Write-Host '=== 8. summary ==='
Write-Host "  email       : $email"
Write-Host "  old userId  : $oldUid"
Write-Host "  new userId  : $($again.data.user.userId)"
Write-Host "  re-register worked: $($again.code -eq 0 -and $again.data.user.userId -ne $oldUid)"
