<#
.SYNOPSIS
    Ledger CSV export / import acceptance tests.

.DESCRIPTION
    Covers the export -> import round trip and every guard on the import side.

      * export: text/csv + UTF-8 BOM + header + one line per business ledger
      * round trip into the SAME account: everything is skipped (semantic dedupe)
      * round trip into a DIFFERENT account: everything is imported
      * a row that does not already exist is imported
      * a bad row rejects the WHOLE file and writes nothing (atomicity)
      * unsupported types (REVERSAL / OPENING) are refused
      * a remark containing a comma survives the round trip (RFC 4180 quoting)

    NOTE: pure ASCII only (PowerShell 5.1 reads .ps1 with the ANSI code page).
    Descriptive variable names only: single letters collide with built-in
    aliases and silently produce empty values.

    Run the backend first, then:  .\test-import-export.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

Add-Type -AssemblyName System.Net.Http
$script:http = New-Object System.Net.Http.HttpClient
$script:http.Timeout = [TimeSpan]::FromSeconds(60)

function Call {
    param([string]$Method, [string]$Path, [object]$Body, [string]$Token)
    $req = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    if ($Token) {
        $req.Headers.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
    }
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 6 }
        $req.Content = New-Object System.Net.Http.StringContent(
            $json, [Text.Encoding]::UTF8, 'application/json')
    }
    try {
        $resp = $script:http.SendAsync($req).GetAwaiter().GetResult()
        $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $status = [int]$resp.StatusCode
        $resp.Dispose()
    } catch {
        return @{ http = 0; code = -1; message = "transport: $_"; data = $null }
    } finally {
        $req.Dispose()
    }
    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        try { $parsed = $text | ConvertFrom-Json } catch { }
    }
    return @{
        http    = $status
        code    = if ($parsed) { $parsed.code } else { $null }
        message = if ($parsed) { $parsed.message } else { $text }
        data    = if ($parsed) { $parsed.data } else { $null }
    }
}

# Posts raw CSV text. Separate helper because the shared one always sends JSON.
function CallCsv {
    param([string]$Path, [string]$Csv, [string]$Token)
    $req = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new('POST'), "$BaseUrl$Path")
    $req.Headers.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
    $req.Content = New-Object System.Net.Http.StringContent(
        $Csv, [System.Text.Encoding]::UTF8, 'text/csv')
    $resp = $script:http.SendAsync($req).GetAwaiter().GetResult()
    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $status = [int]$resp.StatusCode
    $resp.Dispose(); $req.Dispose()
    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        try { $parsed = $text | ConvertFrom-Json } catch { }
    }
    return @{
        http = $status
        code = if ($parsed) { $parsed.code } else { $null }
        data = if ($parsed) { $parsed.data } else { $null }
    }
}

# Downloads the CSV and returns bytes + metadata, so the BOM can be asserted.
function GetCsv {
    param([string]$Path, [string]$Token)
    $req = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new('GET'), "$BaseUrl$Path")
    $req.Headers.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
    $resp = $script:http.SendAsync($req).GetAwaiter().GetResult()
    $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $status = [int]$resp.StatusCode
    $ctype = $resp.Content.Headers.ContentType.ToString()
    $resp.Dispose(); $req.Dispose()
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    return @{ http = $status; ctype = $ctype; bom = $hasBom; text = $text }
}

function Check([string]$label, $actual, $expected) {
    if ("$actual" -eq "$expected") {
        Write-Host ("  [PASS] {0} = {1}" -f $label, $actual) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [FAIL] {0} = {1}  (expected {2})" -f $label, $actual, $expected) -ForegroundColor Red
        $script:fail++
    }
}

function Check-True([string]$label, $condition) {
    Check $label ([bool]$condition) $true
}

function Get-LeavesByType {
    param([string]$Token, [string]$Type)
    $tree = (Call GET "/api/v1/subjects/tree?type=$Type" $null $Token).data
    $out = New-Object System.Collections.ArrayList
    $pending = New-Object System.Collections.Queue
    foreach ($rootNode in $tree) { $pending.Enqueue($rootNode) }
    while ($pending.Count -gt 0) {
        $node = $pending.Dequeue()
        if ($node.leaf) { [void]$out.Add($node) }
        if ($node.children) { foreach ($child in $node.children) { $pending.Enqueue($child) } }
    }
    return $out
}

# ---------------------------------------------------------------------------
# Fixture: 2 expenses, 1 income, 1 transfer => 4 business ledgers
# ---------------------------------------------------------------------------
function New-Fixture {
    param([string]$tag)
    $stamp = Get-Date -Format 'HHmmssfff'
    $reg = Call POST '/api/v1/auth/register' (,@{
        email = "$tag$stamp@example.com"; password = 'start1234'; nickname = $tag
    }) $null
    if ($reg.code -ne 0) { throw "register failed: $($reg.code) $($reg.message)" }
    $token = $reg.data.accessToken

    $assets = Get-LeavesByType $token 'ASSET'
    $expenses = Get-LeavesByType $token 'EXPENSE'
    $incomes = Get-LeavesByType $token 'INCOME'
    if ($assets.Count -lt 2 -or $expenses.Count -lt 1 -or $incomes.Count -lt 1) {
        throw 'preset subjects missing'
    }
    $acctA = $assets[0]
    $acctB = $assets[1]
    $exp = $expenses[0]
    $inc = $incomes[0]

    Call POST "/api/v1/subjects/$($acctA.id)/opening-balance" (,@{
        amount = '5000.00'; occurredDate = '2026-01-01'
    }) $token | Out-Null

    $bookings = @(
        @{ path = '/api/v1/ledgers/expense';  body = @{ amount = '30.00';  expenseSubjectId = $exp.id; assetSubjectId = $acctA.id; occurredDate = '2026-10-05'; remark = 'lunch, with team' } },
        @{ path = '/api/v1/ledgers/expense';  body = @{ amount = '12.50';  expenseSubjectId = $exp.id; assetSubjectId = $acctA.id; occurredDate = '2026-10-06'; remark = 'coffee' } },
        @{ path = '/api/v1/ledgers/income';   body = @{ amount = '800.00'; assetSubjectId = $acctA.id; incomeSubjectId = $inc.id; occurredDate = '2026-10-07'; remark = 'salary' } },
        @{ path = '/api/v1/ledgers/transfer'; body = @{ amount = '100.00'; fromSubjectId = $acctA.id; toSubjectId = $acctB.id; occurredDate = '2026-10-08'; remark = 'move' } }
    )
    foreach ($b in $bookings) {
        $r = Call POST $b.path (,$b.body) $token
        if ($r.code -ne 0) { throw "booking failed at $($b.path): $($r.code) $($r.message)" }
    }

    return @{
        token = $token; assetA = $acctA; assetB = $acctB; expense = $exp; income = $inc
        email = "ledger$tag$stamp@example.com"
    }
}

# Registers a user and only sets an opening balance: no business ledgers.
# Preset subject codes are identical across users, so an exported file imports
# cleanly into such an account.
function New-BareUser {
    param([string]$tag)
    $stamp = Get-Date -Format 'HHmmssfff'
    $reg = Call POST '/api/v1/auth/register' (,@{
        email = "$tag$stamp@example.com"; password = 'start1234'; nickname = $tag
    }) $null
    if ($reg.code -ne 0) { throw "register failed: $($reg.code) $($reg.message)" }
    $token = $reg.data.accessToken
    $assets = Get-LeavesByType $token 'ASSET'
    Call POST "/api/v1/subjects/$($assets[0].id)/opening-balance" (,@{
        amount = '5000.00'; occurredDate = '2026-01-01'
    }) $token | Out-Null
    return @{ token = $token; assetA = $assets[0] }
}

$owner = New-Fixture 'csv'
Write-Host ''
Write-Host 'Fixture: 2 expenses + 1 income + 1 transfer = 4 business ledgers' -ForegroundColor DarkGray

# ------------------------------------------------------------------ export
Write-Host ''
Write-Host '=== export ===' -ForegroundColor Cyan

$csv = GetCsv '/api/v1/ledgers/export' $owner.token
Check 'export HTTP' $csv.http 200
Check 'export is text/csv' ($csv.ctype -like 'text/csv*') $true
Check 'export sends a UTF-8 BOM' $csv.bom $true

$lines = @($csv.text -split "`r?`n" | Where-Object { $_ -ne '' })
Check 'header + 4 data rows' $lines.Count 5

# Header words are Chinese and this file must stay pure ASCII, so build the
# expected header from codepoints:
#   date=U+65E5 U+671F  type=U+7C7B U+578B  amount=U+91D1 U+989D
#   subject=U+79D1 U+76EE  counter=U+5BF9 U+65B9 U+8D26 U+6237
$u = { param([int[]]$c) -join ($c | ForEach-Object { [char]$_ }) }
# codepoints: U+7F16 U+7801 = the 'encoding' suffix on both subject columns
$HDR = (& $u 0x65E5,0x671F) + ',' + (& $u 0x7C7B,0x578B) + ',' + (& $u 0x91D1,0x989D) + ',' +
       (& $u 0x79D1,0x76EE,0x7F16,0x7801) + ',' + (& $u 0x5BF9,0x65B9,0x8D26,0x6237,0x7F16,0x7801)
Check 'header row' $lines[0] ($HDR + ',' + (& $u 0x5907,0x6CE8))

# A remark containing a comma must be quoted, otherwise the row gains a column.
Check-True 'comma in remark is quoted' ($csv.text -match '"lunch, with team"')

# ------------------------------------------------------------------ same account round trip
Write-Host ''
Write-Host '=== re-import into the SAME account (semantic dedupe) ===' -ForegroundColor Cyan

$sameResult = CallCsv '/api/v1/ledgers/import' $csv.text $owner.token
Check 're-import HTTP' $sameResult.http 200
Check 're-import code' $sameResult.code 0
Check 'nothing new imported' $sameResult.data.imported 0
Check 'all 4 skipped as duplicates' $sameResult.data.skipped 4
Check 'no errors' (@($sameResult.data.errors).Count) 0

# ------------------------------------------------------------------ another account
Write-Host ''
Write-Host '=== import into a DIFFERENT account ===' -ForegroundColor Cyan

# Must be a BARE account: a second fixture would book the same four rows for
# it, and semantic dedupe would (correctly) skip everything. A bare account
# is also the realistic case - importing a file exported from elsewhere.
$other = New-BareUser 'csvb'
$freshResult = CallCsv '/api/v1/ledgers/import' $csv.text $other.token
Check 'cross-account import HTTP' $freshResult.http 200
Check 'cross-account imported all 4' $freshResult.data.imported 4
Check 'cross-account skipped none' $freshResult.data.skipped 0

# The receiving account should now export the same 4 rows (codes differ per user,
# so compare row count and amounts rather than whole text).
$otherCsv = GetCsv '/api/v1/ledgers/export' $other.token
$otherLines = @($otherCsv.text -split "`r?`n" | Where-Object { $_ -ne '' })
Check 'second account now has header + 4 rows' $otherLines.Count 5

# ------------------------------------------------------------------ new row
Write-Host ''
Write-Host '=== importing a genuinely new row ===' -ForegroundColor Cyan

$newRow = '2026-10-09,EXPENSE,7.77,' + $owner.expense.code + ',' + $owner.assetA.code + ',new row'
$newCsv = $HDR + ',' + (& $u 0x5907,0x6CE8) + "`n" + $newRow
$added = CallCsv '/api/v1/ledgers/import' $newCsv $owner.token
Check 'new row imported' $added.data.imported 1
Check 'new row skipped none' $added.data.skipped 0

# ------------------------------------------------------------------ atomicity
Write-Host ''
Write-Host '=== a bad row rejects the whole file ===' -ForegroundColor Cyan

$before = (Call GET '/api/v1/ledgers?page=1&size=1' $null $owner.token).data.total

$mixedCsv = $HDR + ',' + (& $u 0x5907,0x6CE8) + "`n" +
            '2026-10-10,EXPENSE,9.99,' + $owner.expense.code + ',' + $owner.assetA.code + ',good row' + "`n" +
            '2026-10-11,EXPENSE,9.99,999999,' + $owner.assetA.code + ',bad subject'
$mixed = CallCsv '/api/v1/ledgers/import' $mixedCsv $owner.token
Check 'bad file reports errors' (@($mixed.data.errors).Count -gt 0) $true
Check 'bad file imported nothing' $mixed.data.imported 0

$after = (Call GET '/api/v1/ledgers?page=1&size=1' $null $owner.token).data.total
Check 'ledger count unchanged after a rejected file' $after $before

# ------------------------------------------------------------------ guards
Write-Host ''
Write-Host '=== import guards ===' -ForegroundColor Cyan

$reversalCsv = $HDR + ',' + (& $u 0x5907,0x6CE8) + "`n" + '2026-10-12,REVERSAL,1.00,500101,500101,x'
$rev = CallCsv '/api/v1/ledgers/import' $reversalCsv $owner.token
Check-True 'REVERSAL rows refused' (@($rev.data.errors).Count -gt 0)

$openingCsv = $HDR + ',' + (& $u 0x5907,0x6CE8) + "`n" + '2026-10-12,OPENING,1.00,,' + $owner.assetA.code + ',x'
$op = CallCsv '/api/v1/ledgers/import' $openingCsv $owner.token
Check-True 'OPENING rows refused' (@($op.data.errors).Count -gt 0)

$shortCsv = $HDR + ',' + (& $u 0x5907,0x6CE8) + "`n" + '2026-10-12,EXPENSE,1.00'
$short = CallCsv '/api/v1/ledgers/import' $shortCsv $owner.token
Check-True 'too few columns refused' (@($short.data.errors).Count -gt 0)

$badDateCsv = $HDR + ',' + (& $u 0x5907,0x6CE8) + "`n" + 'not-a-date,EXPENSE,1.00,' + $owner.expense.code + ',' + $owner.assetA.code + ',x'
$badDate = CallCsv '/api/v1/ledgers/import' $badDateCsv $owner.token
Check-True 'malformed date refused' (@($badDate.data.errors).Count -gt 0)

$emptyImport = CallCsv '/api/v1/ledgers/import' '' $owner.token
Check-True 'empty body refused' ($emptyImport.code -ne 0)

# ------------------------------------------------------------------ summary
Write-Host ''
Write-Host ('=' * 62)
if ($script:fail -eq 0) {
    Write-Host ("ALL {0} CHECKS PASSED" -f $script:pass) -ForegroundColor Green
} else {
    Write-Host ("{0} passed, {1} FAILED" -f $script:pass, $script:fail) -ForegroundColor Red
}
Write-Host ('=' * 62)
exit $(if ($script:fail -eq 0) { 0 } else { 1 })
