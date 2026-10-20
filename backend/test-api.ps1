<#
.SYNOPSIS
    End-to-end acceptance test for the ledger core.

.DESCRIPTION
    Exercises the double-entry core against a running backend:

      AC-01  global debit/credit balance holds (diff = 0.00)
      AC-02  cached account balance matches the entry sum (no drift)
      AC-03  opening balance creates a balanced voucher (asset / equity)
      AC-04  an expense moves the paying account and the expense subject
      AC-05  a reversal restores both to their pre-expense values
      AC-06  replaying a write with the same idempotency key is a no-op
      plus     non-leaf / same-account / double-reversal guards

    Two conventions from docs/system design doc section 6.1.4 shape how this test
    interprets responses, and getting them wrong produces very confusing
    results:

      * Business errors are returned as HTTP 200 with a NON-ZERO `code` in the
        body. Only authentication (401), authorisation (403) and unexpected
        server faults (500) use non-2xx statuses. So "rejected" means
        `code != 0`, NOT "the call threw".

      * Money is transmitted as strings ("30.00") to avoid JSON floating point
        loss, so comparisons are string comparisons.

    Subjects are located by subject_code, never by display name, so this file
    stays pure ASCII. Windows PowerShell 5.1 reads .ps1 using the system ANSI
    code page, so a Chinese literal inside a script would be mangled into a
    parse error.

    Subject codes used (docs/database design doc section 6):
      100104    Alipay   (ALIPAY)
      100102    bank card (BANK)
      500101    food      (non-leaf expense group)
      50010102  lunch     (leaf expense)

    Run the backend first:  .\build.ps1 run
    Then:                   .\test-api.ps1

.PARAMETER BaseUrl
    Backend base URL. Default http://127.0.0.1:8080
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$script:flat = @()

# ---------------------------------------------------------------------------
# HTTP helper.
#
# Returns a result object instead of throwing:
#     @{ http = <status>; ok = <status -lt 400>; code = <body code>;
#        message = <body message or transport error>; data = <body data> }
#
# Not throwing is deliberate. Throwing would conflate three very different
# outcomes -- transport failure, HTTP-level failure, and a business rejection
# carried in a 200 body -- and the guards below need to distinguish them.
#
# Two Windows PowerShell 5.1 traps are also avoided here:
#   1. Splatting -Headers into Invoke-RestMethod from inside a function
#      silently drops the Authorization header on POST requests.
#   2. A bare hashtable passed as a positional argument is treated as a
#      PARAMETER COLLECTION, not as one object:
#          Invoke-Api POST '/p' @{ a = 1 } $token
#      binds `a` as a named parameter, leaves $Body null, and pushes $token
#      into $Body -> the request goes out with no body and no auth header.
# Hence HttpClient with the header set explicitly, and every write call below
# wraps its payload as (,@{ ... }) -- the leading comma makes it a one-element
# array so it stops being read as a parameter collection.
# ---------------------------------------------------------------------------
Add-Type -AssemblyName System.Net.Http

$script:http = New-Object System.Net.Http.HttpClient
$script:http.Timeout = [TimeSpan]::FromSeconds(30)

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$Token,
        [string]$IdempotencyKey
    )

    $req = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")

    if ($Token) {
        $req.Headers.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token)
    }
    if ($IdempotencyKey) {
        $req.Headers.Add('Idempotency-Key', $IdempotencyKey) | Out-Null
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
        return @{ http = 0; ok = $false; code = -1; message = "transport: $_"; data = $null }
    } finally {
        $req.Dispose()
    }

    if ([string]::IsNullOrWhiteSpace($text)) {
        return @{ http = $status; ok = ($status -lt 400); code = $null; message = ''; data = $null }
    }

    $body = $null
    try { $body = $text | ConvertFrom-Json } catch { }

    if ($null -eq $body) {
        return @{ http = $status; ok = ($status -lt 400); code = $null; message = $text; data = $null }
    }
    return @{
        http    = $status
        ok      = ($status -lt 400)
        code    = $body.code
        message = $body.message
        data    = $body.data
    }
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

# A business rejection: HTTP 2xx but a non-zero business code.
function Check-Rejected([string]$label, $result, $expectedCode) {
    $rejected = ($result.ok -and $result.code -ne 0)
    Check $label $rejected $true
    if ($rejected) {
        Write-Host ("        code={0} msg={1}" -f $result.code, $result.message) -ForegroundColor DarkGray
    } else {
        Write-Host ("        NOT rejected: http={0} code={1}" -f $result.http, $result.code) -ForegroundColor Yellow
    }
    if ($expectedCode) { Check "$label (code)" $result.code $expectedCode }
}

function Flatten($nodes) {
    foreach ($n in $nodes) {
        $script:flat += $n
        if ($n.children) { Flatten $n.children }
    }
}

function ByCode([string]$code) {
    return $script:flat | Where-Object { $_.code -eq $code } | Select-Object -First 1
}

function Get-Balance([int]$subjectId, [string]$tok) {
    return (Invoke-Api GET "/api/v1/subjects/$subjectId/balance" $null $tok).data
}

function Get-AccountBalance([int]$subjectId, [string]$tok) {
    $all = (Invoke-Api GET '/api/v1/subjects/accounts' $null $tok).data
    return ($all | Where-Object { $_.id -eq $subjectId } | Select-Object -First 1).balance
}

# ------------------------------------------------------------------ setup
$stamp    = Get-Date -Format 'HHmmss'
$email    = "tester$stamp@example.com"
$password = 'test12345'

Write-Host ''
Write-Host '=== 0. Register a fresh user (initializes the preset subject tree) ===' -ForegroundColor Cyan
$payload = ,@{ email = $email; password = $password; nickname = 'tester' }
$reg = Invoke-Api POST '/api/v1/auth/register' $payload
$token = $reg.data.accessToken
Check 'register code' $reg.code 0
Check-True 'access token issued' ($null -ne $token -and $token.Length -gt 20)
Check 'nickname round-trip' $reg.data.user.nickname 'tester'
Check 'week_start default (Monday)' $reg.data.user.weekStart 1

$tree = Invoke-Api GET '/api/v1/subjects/tree' $null $token
Flatten $tree.data
# The preset tree is 39 nodes: 4 level-1 containers + 35 detail subjects.
# 30 of them are leaves (the ones that accept entries); the other 9 are
# grouping nodes: the 4 containers plus the 5 expense groups that have children.
Check 'preset subject count' $script:flat.Count 39
Check 'leaf subject count' (@($script:flat | Where-Object { $_.leaf }).Count) 30
Check 'non-leaf subject count' (@($script:flat | Where-Object { -not $_.leaf }).Count) 9

Write-Host ''
Write-Host '=== 1. Locate subjects by code ===' -ForegroundColor Cyan
$alipay  = ByCode '100104'
$bank    = ByCode '100102'
$lunch   = ByCode '50010102'
$foodGrp = ByCode '500101'
Check-True 'alipay (100104) found'  ($null -ne $alipay)
Check-True 'bank (100102) found'    ($null -ne $bank)
Check-True 'lunch (50010102) found' ($null -ne $lunch)
Check 'lunch level'            $lunch.level 3
Check 'lunch is leaf'          $lunch.leaf  $true
Check 'food group is not leaf' $foodGrp.leaf $false
Check 'alipay normal side'     $alipay.normalSide 'DEBIT'

$accounts = Invoke-Api GET '/api/v1/subjects/accounts' $null $token
Check 'asset account count' $accounts.data.Count 5
Check 'alipay account kind' ($accounts.data | Where-Object { $_.id -eq $alipay.id }).accountKind 'ALIPAY'
Check 'accounts start at zero' ($accounts.data | Where-Object { $_.id -eq $alipay.id }).balance '0.00'

# ------------------------------------------------------------------ opening
Write-Host ''
Write-Host '=== 2. Opening balance 1000.00 on alipay (debit asset / credit equity) ===' -ForegroundColor Cyan
$payload = ,@{ amount = '1000.00'; occurredDate = '2025-03-01' }
$open = Invoke-Api POST "/api/v1/subjects/$($alipay.id)/opening-balance" $payload $token
Check 'opening voucher type' $open.data.ledger.ledgerType 'OPENING'
Check 'opening entry count'  $open.data.ledger.entries.Count 2
Check 'opening balance'      $open.data.balance '1000.00'
$openCredit = $open.data.ledger.entries | Where-Object { $_.direction -eq 'CREDIT' } | Select-Object -First 1
Check 'opening credit side is equity' $openCredit.subjectType 'EQUITY'
Check 'alipay account list balance' (Get-AccountBalance $alipay.id $token) '1000.00'

# ------------------------------------------------------------------ expense
Write-Host ''
Write-Host '=== 3. Expense 30.00 on lunch paid from alipay (debit expense / credit asset) ===' -ForegroundColor Cyan
# The same key is replayed in step 10 to prove a retry does not double-book.
$expKey  = [guid]::NewGuid().ToString()
$payload = ,@{
    amount           = '30.00'
    expenseSubjectId = $lunch.id
    assetSubjectId   = $alipay.id
    occurredDate     = '2025-03-05'
    remark           = 'lunch'
}
$exp = Invoke-Api POST '/api/v1/ledgers/expense' $payload $token $expKey
Check 'expense code'        $exp.code 0
Check 'expense state'       $exp.data.state 'POSTED'
Check 'expense entry count' $exp.data.entries.Count 2
Check-True 'ledgerNo generated' ($exp.data.ledgerNo -like 'LE20250305*')

$debitLine  = $exp.data.entries | Where-Object { $_.direction -eq 'DEBIT' }  | Select-Object -First 1
$creditLine = $exp.data.entries | Where-Object { $_.direction -eq 'CREDIT' } | Select-Object -First 1
Check 'debit side is lunch'   $debitLine.subjectCode  '50010102'
Check 'credit side is alipay' $creditLine.subjectCode '100104'
Check 'debit amount'  $debitLine.amount  '30.00'
Check 'credit amount' $creditLine.amount '30.00'
$ledgerId = $exp.data.ledgerId

$alipayBal = Get-Balance $alipay.id $token
Check 'alipay cached balance after expense' $alipayBal.cachedBalance '970.00'
Check 'cached equals computed (no drift)'   $alipayBal.consistent $true
Check 'drift is zero'                       $alipayBal.drift '0.00'
Check 'lunch signed sum after expense' (Get-Balance $lunch.id $token).signedSum '30.00'
Check 'alipay account list balance'    (Get-AccountBalance $alipay.id $token) '970.00'

# ------------------------------------------------------------------ reconcile
Write-Host ''
Write-Host '=== 4. Reconcile: global balance + account drift (AC-01, AC-02) ===' -ForegroundColor Cyan
$chk = Invoke-Api GET '/api/v1/ledgers/balance-check' $null $token
Check 'global debit/credit diff' $chk.data.globalBalance.diff '0.00'
Check 'global balanced'          $chk.data.globalBalanced $true
Check 'account drift rows'       @($chk.data.accountDrift).Count 0
Check 'account balanced'         $chk.data.accountBalanced $true

# ------------------------------------------------------------------ transfer
Write-Host ''
Write-Host '=== 5. Transfer 200.00 alipay -> bank (asset to asset) ===' -ForegroundColor Cyan
$payload = ,@{
    amount        = '200.00'
    fromSubjectId = $alipay.id
    toSubjectId   = $bank.id
    occurredDate  = '2025-03-06'
    remark        = 'move funds'
}
$tr = Invoke-Api POST '/api/v1/ledgers/transfer' $payload $token
Check 'transfer type'        $tr.data.ledgerType 'TRANSFER'
Check 'transfer entry count' $tr.data.entries.Count 2
Check 'alipay after transfer (970-200)' (Get-AccountBalance $alipay.id $token) '770.00'
Check 'bank after transfer (0+200)'     (Get-AccountBalance $bank.id   $token) '200.00'

# ------------------------------------------------------------------ reversal
Write-Host ''
Write-Host '=== 6. Reverse the expense (AC-05) ===' -ForegroundColor Cyan
$payload = ,@{ reason = 'wrong entry' }
$rev = Invoke-Api POST "/api/v1/ledgers/$ledgerId/reverse" $payload $token
Check 'reversal voucher type'       $rev.data.ledgerType 'REVERSAL'
Check 'reversal entry count'        $rev.data.entries.Count 2
Check 'reversal points at original' $rev.data.reversalOfId "$ledgerId"

$revDebit  = $rev.data.entries | Where-Object { $_.direction -eq 'DEBIT' }  | Select-Object -First 1
$revCredit = $rev.data.entries | Where-Object { $_.direction -eq 'CREDIT' } | Select-Object -First 1
Check 'reversal debit side is asset'    $revDebit.subjectCode  '100104'
Check 'reversal credit side is expense' $revCredit.subjectCode '50010102'

Check 'lunch net back to 0.00' (Get-Balance $lunch.id $token).signedSum '0.00'

$orig = Invoke-Api GET "/api/v1/ledgers/$ledgerId" $null $token
Check 'original state'          $orig.data.state 'REVERSED'
Check 'original reversedAmount' $orig.data.reversedAmount '30.00'
# Alipay now holds: 1000 opening - 30 expense - 200 transfer + 30 reversal = 800.
# The reversal cancels the expense only; the transfer is a separate voucher and
# still stands, so 770 would be wrong here.
Check 'alipay balance after reversal (1000-30-200+30)' (Get-AccountBalance $alipay.id $token) '800.00'

# ------------------------------------------------------------------ guards
Write-Host ''
Write-Host '=== 7. Guard: reversing the same voucher twice ===' -ForegroundColor Cyan
$payload = ,@{ reason = 'again' }
Check-Rejected 'double reversal rejected' (Invoke-Api POST "/api/v1/ledgers/$ledgerId/reverse" $payload $token) 1403

Write-Host ''
Write-Host '=== 8. Guard: transfer to the same account ===' -ForegroundColor Cyan
$payload = ,@{ amount = '1.00'; fromSubjectId = $alipay.id; toSubjectId = $alipay.id; occurredDate = '2025-03-07' }
Check-Rejected 'same-account transfer rejected' (Invoke-Api POST '/api/v1/ledgers/transfer' $payload $token) 1405

Write-Host ''
Write-Host '=== 9. Guard: booking against a non-leaf subject (BR-10) ===' -ForegroundColor Cyan
$payload = ,@{ amount = '10.00'; expenseSubjectId = $foodGrp.id; assetSubjectId = $alipay.id; occurredDate = '2025-03-08' }
Check-Rejected 'non-leaf subject rejected' (Invoke-Api POST '/api/v1/ledgers/expense' $payload $token) 1304

Write-Host ''
Write-Host '=== 10. Idempotency: replaying the expense with the same key (AC-06) ===' -ForegroundColor Cyan
$payload = ,@{
    amount           = '30.00'
    expenseSubjectId = $lunch.id
    assetSubjectId   = $alipay.id
    occurredDate     = '2025-03-05'
    remark           = 'lunch'
}
$replay = Invoke-Api POST '/api/v1/ledgers/expense' $payload $token $expKey
Check 'replay returns the same voucher' $replay.data.ledgerId "$ledgerId"
Check 'replay entry count still two'    $replay.data.entries.Count 2

$expTotal = (Invoke-Api GET '/api/v1/ledgers?page=1&size=50&ledgerType=EXPENSE' $null $token).data.total
Check 'only one expense voucher exists' $expTotal 1
Check 'replay did not change the balance' (Get-AccountBalance $alipay.id $token) '800.00'

Write-Host ''
Write-Host '=== 11. Pagination ===' -ForegroundColor Cyan
$page = Invoke-Api GET '/api/v1/ledgers?page=1&size=2' $null $token
Check 'page size respected' @($page.data.records).Count 2
Check-True 'total populated' ($page.data.total -ge 3)

Write-Host ''
Write-Host '=== 12. Final reconcile ===' -ForegroundColor Cyan
$final = Invoke-Api GET '/api/v1/ledgers/balance-check' $null $token
Check 'final global diff' $final.data.globalBalance.diff '0.00'
Check 'final drift rows'  @($final.data.accountDrift).Count 0

Write-Host ''
Write-Host '=== 13. Logout blacklists the token (deliberately last) ===' -ForegroundColor Cyan
# This must run last: logout adds the token to the blacklist, so every later
# call with it correctly fails. This is the one case that uses a non-2xx
# status, so it is asserted on `ok` rather than on `code`.
$logout = Invoke-Api POST '/api/v1/auth/logout' $null $token
Check 'logout succeeded' $logout.code 0
$after = Invoke-Api GET '/api/v1/subjects/accounts' $null $token
Check 'token rejected after logout' $after.ok $false
Check 'rejection is HTTP 401' $after.http 401

Write-Host ''
# ------------------------------------------------------------------ media type
# A wrong Content-Type used to fall through to the catch-all handler and answer
# 1901 "the system glitched", with a full stack trace logged as ERROR. Both were
# misleading: the caller could not tell it was their own mistake, and ops would
# think the service was broken. It must now be 415 with a message naming the
# media types the endpoint accepts.
Write-Host ''
Write-Host '=== wrong Content-Type is a client error, not a server fault ===' -ForegroundColor Cyan

$badReq = New-Object System.Net.Http.HttpRequestMessage(
    [System.Net.Http.HttpMethod]::new('POST'), "$BaseUrl/api/v1/auth/register")
$badContent = New-Object System.Net.Http.StringContent('{"email":"x@example.com","password":"whatever1"}')
$badContent.Headers.ContentType =
    [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('text/plain')
$badReq.Content = $badContent
$badResp = $script:http.SendAsync($badReq).GetAwaiter().GetResult()
$badBody = $badResp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
$badStatus = [int]$badResp.StatusCode
$badResp.Dispose(); $badReq.Dispose()
$badParsed = $badBody | ConvertFrom-Json

Check 'unsupported media type returns HTTP 415' $badStatus 415
Check 'it is not reported as an internal error' ($badParsed.code -ne 1901) $true
Check 'it is reported as a parameter problem' $badParsed.code 1101
Check-True 'the message names the accepted media types' ($badParsed.message -match 'json')

Write-Host ('=' * 62)
if ($script:fail -eq 0) {
    Write-Host ("ALL {0} CHECKS PASSED" -f $script:pass) -ForegroundColor Green
} else {
    Write-Host ("{0} passed, {1} FAILED" -f $script:pass, $script:fail) -ForegroundColor Red
}
Write-Host ('=' * 62)
if ($script:fail -gt 0) { exit 1 }
