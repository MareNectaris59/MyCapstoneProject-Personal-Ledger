<#
.SYNOPSIS
    Acceptance tests for the criteria the main suite does not cover.

.DESCRIPTION
    test-api.ps1 covers AC-01, AC-02, AC-06, AC-12 (and AC-04 implicitly).
    This file covers the rest of the ledger-core criteria:

      AC-03  transfer conservation -- the sum of all asset balances is unchanged
             by a transfer (test-api only checked the two accounts individually)
      AC-04  reversal equivalence  -- asserted EXPLICITLY: balance after reversal
             equals the balance captured before the expense
      AC-05  partial reversal      -- reverse 33.33 of 100.00, the remaining
             66.67 is still reversible, then the balance returns to its start
      AC-07  concurrency safety    -- 100 concurrent 1.00 expenses on ONE account
             must leave the balance down by exactly 100.00 (validates row locking
             and the fixed ascending subject-id lock order)
      AC-13  permission isolation  -- user A cannot reach user B's data by
             tampering with ids

    AC-08/09/10/11 need the budget and analysis modules, which do not exist yet;
    they will be added when those modules land.

    Conventions (docs/ system design doc, section 6.1.4), same as test-api.ps1:
      * business errors are HTTP 200 with a NON-ZERO `code`; only 401/403/500
        are non-2xx. "rejected" therefore means code != 0.
      * money is transmitted as strings, so comparisons are string comparisons.

    NOTE: pure ASCII only. Windows PowerShell 5.1 reads .ps1 with the system
    ANSI code page, so a Chinese literal here would be mangled and break parsing.

    Subject codes (docs/ database design doc, section 6):
      100102  BANK    100104  ALIPAY    100103  WECHAT
      50010102  LUNCH (leaf expense)

    Run the backend first, then:  .\test-acceptance.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [int]$Concurrency = 100
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$script:flat = @()

Add-Type -AssemblyName System.Net.Http
$script:http = New-Object System.Net.Http.HttpClient
$script:http.Timeout = [TimeSpan]::FromSeconds(60)

# ---------------------------------------------------------------------------
# HTTP helper. Never throws; returns @{ http; ok; code; message; data }.
# Payloads must be wrapped as (,@{ ... }) -- a bare hashtable would be read as
# a PARAMETER COLLECTION and silently drop both the body and the token.
# ---------------------------------------------------------------------------
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

function Get-AccountBalance([int]$subjectId, [string]$tok) {
    $all = (Invoke-Api GET '/api/v1/subjects/accounts' $null $tok).data
    return ($all | Where-Object { $_.id -eq $subjectId } | Select-Object -First 1).balance
}

# Sum of all asset balances. AC-03 asserts this is invariant under a transfer.
function Get-TotalAssets([string]$tok) {
    $all = (Invoke-Api GET '/api/v1/subjects/accounts' $null $tok).data
    $sum = 0.0
    foreach ($a in $all) { $sum += [double]$a.balance }
    return $sum.ToString('0.00')
}

# Register a user and return a session object with the preset tree flattened.
function New-Session([string]$tag) {
    $stamp = Get-Date -Format 'HHmmssfff'
    $email = "$tag$stamp@example.com"
    $reg = Invoke-Api POST '/api/v1/auth/register' (,@{ email = $email; password = 'test12345'; nickname = $tag })
    if ($reg.code -ne 0) { throw "register failed for $tag : $($reg.code) $($reg.message)" }

    $saved = $script:flat
    $script:flat = @()
    $tree = Invoke-Api GET '/api/v1/subjects/tree' $null $reg.data.accessToken
    Flatten $tree.data
    $sess = @{
        token = $reg.data.accessToken
        email = $email
        user  = $reg.data.user
        flat  = $script:flat
    }
    $script:flat = $saved
    return $sess
}

function Sess-ByCode($sess, [string]$code) {
    return $sess.flat | Where-Object { $_.code -eq $code } | Select-Object -First 1
}

# ------------------------------------------------------------------ AC-03 / AC-04
Write-Host ''
Write-Host '=== AC-03 / AC-04: transfer conservation and reversal equivalence ===' -ForegroundColor Cyan

$A = New-Session 'accA'
$tok = $A.token
$tokA = $A.token
$alipay = Sess-ByCode $A '100104'
$bank   = Sess-ByCode $A '100102'
$lunch  = Sess-ByCode $A '50010102'

# Fund two accounts so the transfer has something to move.
Invoke-Api POST "/api/v1/subjects/$($alipay.id)/opening-balance" (,@{ amount = '1000.00'; occurredDate = '2025-03-01' }) $tok | Out-Null
Invoke-Api POST "/api/v1/subjects/$($bank.id)/opening-balance"   (,@{ amount = '500.00';  occurredDate = '2025-03-01' }) $tok | Out-Null

$totalBefore = Get-TotalAssets $tok
Check 'total assets before transfer' $totalBefore '1500.00'

# --- AC-03: a transfer must conserve total assets -------------------------
Write-Host ''
Write-Host '  -- AC-03 transfer 200.00 alipay -> bank --' -ForegroundColor DarkCyan
$tr = Invoke-Api POST '/api/v1/ledgers/transfer' (,@{
    amount = '200.00'; fromSubjectId = $alipay.id; toSubjectId = $bank.id
    occurredDate = '2025-03-02'; remark = 'AC-03'
}) $tok
Check 'transfer accepted' $tr.code 0

$totalAfter = Get-TotalAssets $tok
Check 'AC-03 total assets unchanged by transfer' $totalAfter $totalBefore
Check 'AC-03 source account debited'  (Get-AccountBalance $alipay.id $tok) '800.00'
Check 'AC-03 target account credited' (Get-AccountBalance $bank.id   $tok) '700.00'

# --- AC-04: reversal returns the balance to its pre-expense value ---------
Write-Host ''
Write-Host '  -- AC-04 expense 30.00 then full reversal --' -ForegroundColor DarkCyan
$alipayBefore = Get-AccountBalance $alipay.id $tok

$exp = Invoke-Api POST '/api/v1/ledgers/expense' (,@{
    amount = '30.00'; expenseSubjectId = $lunch.id; assetSubjectId = $alipay.id
    occurredDate = '2025-03-03'; remark = 'AC-04'
}) $tok
Check 'expense accepted' $exp.code 0
Check 'AC-04 balance moved down by 30.00' (Get-AccountBalance $alipay.id $tok) '770.00'

$rev = Invoke-Api POST "/api/v1/ledgers/$($exp.data.ledgerId)/reverse" (,@{ reason = 'AC-04' }) $tok
Check 'reversal accepted' $rev.code 0
Check 'AC-04 balance equals pre-expense value' (Get-AccountBalance $alipay.id $tok) $alipayBefore

# ------------------------------------------------------------------ AC-05
Write-Host ''
Write-Host '=== AC-05: partial reversal (reverse 33.33 of 100.00, then the rest) ===' -ForegroundColor Cyan

$start = Get-AccountBalance $alipay.id $tok
$p = Invoke-Api POST '/api/v1/ledgers/expense' (,@{
    amount = '100.00'; expenseSubjectId = $lunch.id; assetSubjectId = $alipay.id
    occurredDate = '2025-03-04'; remark = 'AC-05'
}) $tok
Check 'expense 100.00 accepted' $p.code 0

$startNum = [double]$start
Check 'balance after 100.00 expense' (Get-AccountBalance $alipay.id $tok) ($startNum - 100).ToString('0.00')

# First: ask for MORE than remains -> must be refused with 1404.
$over = Invoke-Api POST "/api/v1/ledgers/$($p.data.ledgerId)/reverse" (,@{ amount = '150.00'; reason = 'too much' }) $tok
Check-Rejected 'AC-05 over-reversal refused' $over 1404

# Reverse 33.33 -> 66.67 remains reversible.
$r1 = Invoke-Api POST "/api/v1/ledgers/$($p.data.ledgerId)/reverse" (,@{ amount = '33.33'; reason = 'AC-05 part 1' }) $tok
Check 'AC-05 partial reversal accepted' $r1.code 0
Check 'AC-05 balance back up by 33.33' (Get-AccountBalance $alipay.id $tok) ($startNum - 66.67).ToString('0.00')

$detail = Invoke-Api GET "/api/v1/ledgers/$($p.data.ledgerId)" $null $tok
Check 'AC-05 original state is PARTIALLY_REVERSED' $detail.data.state 'PARTIALLY_REVERSED'

# Second: 66.67 is exactly what is left.
$r2 = Invoke-Api POST "/api/v1/ledgers/$($p.data.ledgerId)/reverse" (,@{ amount = '66.67'; reason = 'AC-05 part 2' }) $tok
Check 'AC-05 remainder reversal accepted' $r2.code 0
Check 'AC-05 balance fully restored' (Get-AccountBalance $alipay.id $tok) $start

$detail2 = Invoke-Api GET "/api/v1/ledgers/$($p.data.ledgerId)" $null $tok
Check 'AC-05 original state is REVERSED' $detail2.data.state 'REVERSED'

# Third: nothing left -> must be refused.
$r3 = Invoke-Api POST "/api/v1/ledgers/$($p.data.ledgerId)/reverse" (,@{ amount = '1.00'; reason = 'AC-05 extra' }) $tok
Check-Rejected 'AC-05 reversal after fully reversed refused' $r3

# ------------------------------------------------------------------ AC-13
Write-Host ''
Write-Host '=== AC-13: permission isolation (user A tampering with user B ids) ===' -ForegroundColor Cyan

$B = New-Session 'accB'
$tokB = $B.token
$bAlipay = Sess-ByCode $B '100104'
$bLunch  = Sess-ByCode $B '50010102'

# Give B some private data so there is something to leak.
Invoke-Api POST "/api/v1/subjects/$($bAlipay.id)/opening-balance" (,@{ amount = '777.00'; occurredDate = '2025-03-01' }) $tokB | Out-Null
$bExp = Invoke-Api POST '/api/v1/ledgers/expense' (,@{
    amount = '11.00'; expenseSubjectId = $bLunch.id; assetSubjectId = $bAlipay.id
    occurredDate = '2025-03-05'; remark = 'B private'
}) $tokB
Check 'B can book its own expense' $bExp.code 0

# A, holding a valid token of its OWN, now addresses B's resources by id.
$leak1 = Invoke-Api GET "/api/v1/subjects/$($bAlipay.id)/balance" $null $tokA
Check-Rejected 'AC-13 A cannot read B subject balance' $leak1 1301

$leak2 = Invoke-Api GET "/api/v1/ledgers/$($bExp.data.ledgerId)" $null $tokA
Check-Rejected 'AC-13 A cannot read B ledger detail' $leak2 1402

$leak3 = Invoke-Api POST "/api/v1/ledgers/$($bExp.data.ledgerId)/reverse" (,@{ reason = 'hijack' }) $tokA
Check-Rejected 'AC-13 A cannot reverse B ledger' $leak3 1402

$leak4 = Invoke-Api DELETE "/api/v1/subjects/$($bAlipay.id)" $null $tokA
Check-Rejected 'AC-13 A cannot delete B subject' $leak4 1301

$leak5 = Invoke-Api PUT "/api/v1/subjects/$($bAlipay.id)/enabled?enabled=false" $null $tokA
Check-Rejected 'AC-13 A cannot disable B subject' $leak5 1301

$leak6 = Invoke-Api POST "/api/v1/subjects/$($bAlipay.id)/opening-balance" (,@{ amount = '1.00'; occurredDate = '2025-03-06' }) $tokA
Check-Rejected 'AC-13 A cannot set opening balance on B account' $leak6

# A may not book against B's subjects either.
$leak7 = Invoke-Api POST '/api/v1/ledgers/expense' (,@{
    amount = '5.00'; expenseSubjectId = $bLunch.id; assetSubjectId = $alipay.id
    occurredDate = '2025-03-06'; remark = 'cross-tenant'
}) $tokA
Check-Rejected 'AC-13 A cannot book against B expense subject' $leak7

# And B's data must be untouched by all of the above.
Check 'AC-13 B balance unchanged' (Get-AccountBalance $bAlipay.id $tokB) '766.00'
$bDetail = Invoke-Api GET "/api/v1/ledgers/$($bExp.data.ledgerId)" $null $tokB
Check 'AC-13 B ledger still POSTED' $bDetail.data.state 'POSTED'

# A's own view must not contain any of B's subjects.
$myTree = Invoke-Api GET '/api/v1/subjects/tree' $null $tokA
$saved = $script:flat; $script:flat = @(); Flatten $myTree.data
$overlap = @($script:flat | Where-Object { $_.id -eq $bAlipay.id }).Count
$script:flat = $saved
Check 'AC-13 B subject absent from A tree' $overlap 0

# ------------------------------------------------------------------ AC-07
Write-Host ''
Write-Host "=== AC-07: concurrency -- $Concurrency parallel 1.00 expenses on one account ===" -ForegroundColor Cyan
Write-Host '  (validates row locking and the fixed ascending subject-id lock order)' -ForegroundColor DarkGray

$C = New-Session 'accC'
$tokC = $C.token
$cAlipay = Sess-ByCode $C '100104'
$cLunch  = Sess-ByCode $C '50010102'

Invoke-Api POST "/api/v1/subjects/$($cAlipay.id)/opening-balance" (,@{ amount = '500.00'; occurredDate = '2025-03-01' }) $tokC | Out-Null
$balBefore = Get-AccountBalance $cAlipay.id $tokC
Check 'AC-07 starting balance' $balBefore '500.00'

# Fire all requests BEFORE awaiting any of them. This is what makes the test
# meaningful: a sequential loop would never exercise the lock order.
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = New-Object System.Collections.Generic.List[object]
for ($i = 0; $i -lt $Concurrency; $i++) {
    $req = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new('POST'), "$BaseUrl/api/v1/ledgers/expense")
    $req.Headers.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tokC)
    $req.Headers.Add('Idempotency-Key', [guid]::NewGuid().ToString()) | Out-Null
    $payload = @{
        amount = '1.00'; expenseSubjectId = $cLunch.id; assetSubjectId = $cAlipay.id
        occurredDate = '2025-03-07'; remark = "AC-07 #$i"
    } | ConvertTo-Json -Compress
    $req.Content = New-Object System.Net.Http.StringContent(
        $payload, [Text.Encoding]::UTF8, 'application/json')
    $tasks.Add($script:http.SendAsync($req))
}
Write-Host ("  dispatched {0} concurrent requests in {1} ms" -f $Concurrency, $sw.ElapsedMilliseconds) -ForegroundColor DarkGray

$okCount = 0
$failCodes = @{}
$ledgerIds = New-Object System.Collections.Generic.HashSet[string]
foreach ($t in $tasks) {
    try {
        $resp = $t.GetAwaiter().GetResult()
        $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $status = [int]$resp.StatusCode
        $resp.Dispose()
        $body = $null
        try { $body = $text | ConvertFrom-Json } catch { }
        if ($status -lt 400 -and $body -and $body.code -eq 0) {
            $okCount++
            [void]$ledgerIds.Add("$($body.data.ledgerId)")
        } else {
            $key = if ($body) { "$($body.code)" } else { "http$status" }
            $failCodes[$key] = 1 + ($failCodes[$key] | ForEach-Object { $_ })
        }
    } catch {
        $failCodes['exception'] = 1 + ($failCodes['exception'] | ForEach-Object { $_ })
    }
}
$sw.Stop()
Write-Host ("  completed in {0} ms" -f $sw.ElapsedMilliseconds) -ForegroundColor DarkGray

Check 'AC-07 all requests succeeded' $okCount $Concurrency
Check 'AC-07 distinct ledgers created' $ledgerIds.Count $Concurrency
if ($failCodes.Count -gt 0) {
    Write-Host '  failures:' -ForegroundColor Yellow
    foreach ($k in $failCodes.Keys) { Write-Host ("    code {0} x {1}" -f $k, $failCodes[$k]) -ForegroundColor Yellow }
}

# The heart of the criterion: the arithmetic must be exact. A lost update from
# a broken lock shows up here as a balance higher than 400.00.
$balAfter = Get-AccountBalance $cAlipay.id $tokC
Check 'AC-07 final balance is exactly 400.00' $balAfter '400.00'

# Cross-check against the ledger itself: cached balance vs sum of entries.
$acc = (Invoke-Api GET '/api/v1/subjects/accounts' $null $tokC).data |
       Where-Object { $_.id -eq $cAlipay.id } | Select-Object -First 1
Check 'AC-07 cached balance matches account list' $acc.balance '400.00'

$chk = Invoke-Api GET '/api/v1/ledgers/balance-check' $null $tokC
Check-True 'AC-07 global balance holds after concurrency' $chk.data.globalBalanced
Check-True 'AC-07 no account drift after concurrency'    $chk.data.accountBalanced

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
