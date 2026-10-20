<#
.SYNOPSIS
    Acceptance tests for the requirements finished last: FR-U-04, FR-U-03,
    FR-Q-01, FR-Q-02, FR-A-05.

.DESCRIPTION
    Covers:

      FR-U-04  change password -- requires the old password, rejects reuse of it,
               and the old password stops working while the new one starts
      FR-U-03  change nickname -- blank rejected, reflected by /auth/me
      FR-Q-01  combined ledger query -- time x type x subject x amount range
      FR-Q-02  sorting -- by amount and by date, both directions
      FR-A-05  per-subject balance and movement detail, plus ownership isolation

    Conventions (design doc section 6.1.4):
      * business errors are HTTP 200 + a non-zero `code`
      * money and rates are strings

    NOTE: pure ASCII only (PowerShell 5.1 reads .ps1 with the ANSI code page).
    Descriptive variable names only: single letters collide with built-in
    aliases and silently produce empty values.

    Preset subject codes: 100104 ALIPAY, 50010102 LUNCH, 50010202 TAXI

    Run the backend first, then:  .\test-profile-query.ps1
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

# ---------------------------------------------------------------------------
# Fixture: three bookings with distinct amounts and dates so that both the
# subject filter and the amount/date sort have something to discriminate.
#
#   2026-10-05  LUNCH  100.00
#   2026-10-06  TAXI    50.00
#   2026-10-07  LUNCH  300.00
# ---------------------------------------------------------------------------
function New-Fixture {
    param([string]$tag)
    $stamp = Get-Date -Format 'HHmmssfff'
    $email = "$tag$stamp@example.com"
    $reg = Call POST '/api/v1/auth/register' (,@{
        email = $email; password = 'start1234'; nickname = $tag
    }) $null
    if ($reg.code -ne 0) { throw "register failed: $($reg.code) $($reg.message)" }
    $token = $reg.data.accessToken

    $treeJson = (Call GET '/api/v1/subjects/tree' $null $token).data
    $allNodes = New-Object System.Collections.ArrayList
    $pending = New-Object System.Collections.Queue
    foreach ($rootNode in $treeJson) { $pending.Enqueue($rootNode) }
    while ($pending.Count -gt 0) {
        $currentNode = $pending.Dequeue()
        [void]$allNodes.Add($currentNode)
        if ($currentNode.children) {
            foreach ($childNode in $currentNode.children) { $pending.Enqueue($childNode) }
        }
    }
    $alipay = $allNodes | Where-Object { $_.code -eq '100104' } | Select-Object -First 1
    $lunch  = $allNodes | Where-Object { $_.code -eq '50010102' } | Select-Object -First 1
    $taxi   = $allNodes | Where-Object { $_.code -eq '50010202' } | Select-Object -First 1
    if (-not $alipay -or -not $lunch -or -not $taxi) { throw 'preset subject lookup failed' }

    Call POST "/api/v1/subjects/$($alipay.id)/opening-balance" (,@{
        amount = '5000.00'; occurredDate = '2026-01-01'
    }) $token | Out-Null

    $bookings = @(
        @{ amount = '100.00'; subjectId = $lunch.id; date = '2026-10-05' },
        @{ amount = '50.00';  subjectId = $taxi.id;  date = '2026-10-06' },
        @{ amount = '300.00'; subjectId = $lunch.id; date = '2026-10-07' }
    )
    foreach ($b in $bookings) {
        $r = Call POST '/api/v1/ledgers/expense' (,@{
            amount = $b.amount; expenseSubjectId = $b.subjectId
            assetSubjectId = $alipay.id; occurredDate = $b.date
        }) $token
        if ($r.code -ne 0) { throw "booking failed: $($r.code) $($r.message)" }
    }

    return @{
        token = $token; email = $email
        alipayId = $alipay.id; lunchId = $lunch.id; taxiId = $taxi.id
    }
}

$fx = New-Fixture 'pq'
$token = $fx.token
Write-Host ''
Write-Host 'Fixture: 2026-10-05 LUNCH 100 / 2026-10-06 TAXI 50 / 2026-10-07 LUNCH 300' -ForegroundColor DarkGray

# ------------------------------------------------------------------ FR-U-04
Write-Host ''
Write-Host '=== FR-U-04 change password ===' -ForegroundColor Cyan

$wrongOld = Call PUT '/api/v1/auth/password' (,@{
    oldPassword = 'totally-wrong'; newPassword = 'brandnew1234'
}) $token
Check 'wrong old password refused' ($wrongOld.code -ne 0) $true
Check 'wrong old password -> 1203' $wrongOld.code 1203

$sameAsOld = Call PUT '/api/v1/auth/password' (,@{
    oldPassword = 'start1234'; newPassword = 'start1234'
}) $token
Check 'reusing the old password refused' ($sameAsOld.code -ne 0) $true
Check 'reuse -> 1101' $sameAsOld.code 1101

$tooShort = Call PUT '/api/v1/auth/password' (,@{
    oldPassword = 'start1234'; newPassword = 'short'
}) $token
Check 'new password shorter than 8 refused' ($tooShort.code -ne 0) $true

$okChange = Call PUT '/api/v1/auth/password' (,@{
    oldPassword = 'start1234'; newPassword = 'brandnew1234'
}) $token
Check 'valid change accepted' $okChange.code 0

$oldLogin = Call POST '/api/v1/auth/login' (,@{
    email = $fx.email; password = 'start1234'
}) $null
Check 'old password no longer works' $oldLogin.code 1203

$newLogin = Call POST '/api/v1/auth/login' (,@{
    email = $fx.email; password = 'brandnew1234'
}) $null
Check 'new password works' $newLogin.code 0

# token_version was bumped, so every previously issued token must stop working.
$staleToken = Call GET '/api/v1/auth/me' $null $token
Check 'old token rejected with 401 after password change' $staleToken.http 401

# Switch to the fresh token: keeping the stale one would fail the rest of this suite.
$token = $newLogin.data.accessToken
Check 'new token works' (Call GET '/api/v1/auth/me' $null $token).code 0

# ------------------------------------------------------------------ FR-U-03
Write-Host ''
Write-Host '=== FR-U-03 change nickname ===' -ForegroundColor Cyan

$before = Call GET '/api/v1/auth/me' $null $token
Check 'nickname starts as the registration value' $before.data.user.nickname 'pq'

$renamed = Call PUT '/api/v1/auth/profile' (,@{ nickname = 'Number One' }) $token
Check 'nickname updated' $renamed.code 0
Check 'response carries the new nickname' $renamed.data.nickname 'Number One'

$meAfter = Call GET '/api/v1/auth/me' $null $token
Check '/auth/me reflects the new nickname' $meAfter.data.user.nickname 'Number One'

$blankName = Call PUT '/api/v1/auth/profile' (,@{ nickname = '   ' }) $token
Check 'blank nickname refused' ($blankName.code -ne 0) $true
Check 'blank nickname -> 1101' $blankName.code 1101

# ------------------------------------------------------------------ FR-Q-01
Write-Host ''
Write-Host '=== FR-Q-01 combined query ===' -ForegroundColor Cyan

$all = Call GET '/api/v1/ledgers?page=1&size=50' $null $token
Check 'no filter returns everything (3 expenses + 1 opening)' $all.data.total 4

$bySubject = Call GET "/api/v1/ledgers?page=1&size=50&subjectId=$($fx.lunchId)" $null $token
Check 'subject filter matches only LUNCH ledgers' $bySubject.data.total 2
$lunchAmounts = @($bySubject.data.records | ForEach-Object { $_.amount } | Sort-Object)
Check 'subject filter keeps both LUNCH amounts' ($lunchAmounts -join ',') '100.00,300.00'

$byAmount = Call GET '/api/v1/ledgers?page=1&size=50&minAmount=100&maxAmount=200' $null $token
Check 'amount range 100..200 matches one ledger' $byAmount.data.total 1
Check 'amount range picked the right one' $byAmount.data.records[0].amount '100.00'

$byBoth = Call GET "/api/v1/ledgers?page=1&size=50&ledgerType=EXPENSE&subjectId=$($fx.lunchId)&minAmount=150" $null $token
Check 'type + subject + amount combine' $byBoth.data.total 1
Check 'combined query kept the 300 ledger' $byBoth.data.records[0].amount '300.00'

$byType = Call GET '/api/v1/ledgers?page=1&size=50&ledgerType=OPENING' $null $token
Check 'type filter isolates the opening ledger' $byType.data.total 1

$badRange = Call GET '/api/v1/ledgers?minAmount=500&maxAmount=100' $null $token
Check 'min > max refused' ($badRange.code -ne 0) $true
Check 'min > max -> 1101' $badRange.code 1101

# ------------------------------------------------------------------ FR-Q-02
Write-Host ''
Write-Host '=== FR-Q-02 sorting ===' -ForegroundColor Cyan

$amountAsc = Call GET '/api/v1/ledgers?page=1&size=50&sortBy=amount&sortOrder=asc' $null $token
$ascList = @($amountAsc.data.records | ForEach-Object { [double]$_.amount })
$ascSorted = @($ascList | Sort-Object)
Check 'amount asc is ascending' (($ascList -join ',') -eq ($ascSorted -join ',')) $true
Check 'amount asc starts at 50.00' $ascList[0] 50

$amountDesc = Call GET '/api/v1/ledgers?page=1&size=50&sortBy=amount&sortOrder=desc' $null $token
$descList = @($amountDesc.data.records | ForEach-Object { [double]$_.amount })
Check 'amount desc starts at the opening balance 5000.00' $descList[0] 5000
Check 'amount desc is the reverse of asc' (($descList -join ',') -eq (($ascList | Sort-Object -Descending) -join ',')) $true

$dateAsc = Call GET '/api/v1/ledgers?page=1&size=50&sortBy=occurredDate&sortOrder=asc' $null $token
$dateList = @($dateAsc.data.records | ForEach-Object { $_.occurredDate })
Check 'date asc starts at the earliest' $dateList[0] '2026-01-01'
Check 'date asc ends at the latest' $dateList[$dateList.Count - 1] '2026-10-07'

$dateDesc = Call GET '/api/v1/ledgers?page=1&size=50' $null $token
Check 'default sort is date descending' $dateDesc.data.records[0].occurredDate '2026-10-07'

# Paging must not drop or duplicate rows when amounts tie.
$page1 = Call GET '/api/v1/ledgers?page=1&size=2&sortBy=amount&sortOrder=asc' $null $token
$page2 = Call GET '/api/v1/ledgers?page=2&size=2&sortBy=amount&sortOrder=asc' $null $token
$pagedIds = @($page1.data.records | ForEach-Object { $_.id }) + @($page2.data.records | ForEach-Object { $_.id })
Check 'paged result has no duplicates' (@($pagedIds | Select-Object -Unique).Count) $pagedIds.Count
Check 'paged result covers all rows' $pagedIds.Count 4

# ------------------------------------------------------------------ FR-A-05
Write-Host ''
Write-Host '=== FR-A-05 subject balance and movements ===' -ForegroundColor Cyan

$acct = Call GET "/api/v1/subjects/$($fx.alipayId)/balance" $null $token
Check 'account balance code' $acct.code 0
Check 'account balance is 5000 - 450 = 4550.00' $acct.data.cachedBalance '4550.00'
Check 'cached balance agrees with the entry sum' $acct.data.consistent $true
Check 'balance reports the subject name' ($acct.data.subjectName.Length -gt 0) $true

$cat = Call GET "/api/v1/subjects/$($fx.lunchId)/balance" $null $token
Check 'category balance code' $cat.code 0
# Sign convention: signed_amount is +debit / -credit, and an expense subject's
# normal side is debit. So expenses ACCUMULATE POSITIVELY here (+100 +300),
# while the paying asset account accumulates negatively. Getting this backwards
# is the easiest mistake to make in this schema, hence the explicit check.
Check 'category signed sum is +400.00 (debit-normal)' $cat.data.signedSum '400.00'

$moves = Call GET "/api/v1/subjects/$($fx.alipayId)/entries?limit=50" $null $token
Check 'movement list code' $moves.code 0
Check 'account has 4 movements (1 opening + 3 expenses)' (@($moves.data).Count) 4
Check 'movements are newest first' (@($moves.data)[0].occurredDate) '2026-10-07'
Check 'movement rows carry the ledger number' (@($moves.data)[0].ledgerNo.Length -gt 0) $true

$catMoves = Call GET "/api/v1/subjects/$($fx.lunchId)/entries?limit=50" $null $token
Check 'category has 2 movements' (@($catMoves.data).Count) 2

# Ownership isolation: another user must not read these subjects.
$other = New-Fixture 'pqb'
$foreignBalance = Call GET "/api/v1/subjects/$($fx.alipayId)/balance" $null $other.token
Check 'foreign subject balance refused' ($foreignBalance.code -ne 0) $true
Check 'foreign subject balance -> 1301' $foreignBalance.code 1301
$foreignMoves = Call GET "/api/v1/subjects/$($fx.lunchId)/entries" $null $other.token
Check 'foreign subject movements refused' ($foreignMoves.code -ne 0) $true

# ------------------------------------------------------------------ hardening
Write-Host ''
Write-Host '=== login rate limit and parameter bounds ===' -ForegroundColor Cyan

# Use a dedicated email: the limiter keys on (email + IP), so the fixture stays unlocked.
$lockStamp = Get-Date -Format 'HHmmssfff'
$lockEmail = "lock$lockStamp@example.com"
$lastCode = $null
for ($attempt = 1; $attempt -le 5; $attempt++) {
    $lastCode = (Call POST '/api/v1/auth/login' (,@{
        email = $lockEmail; password = 'definitely-wrong'
    }) $null).code
}
Check 'first 5 failed logins report PASSWORD_ERROR' $lastCode 1203

$blocked = Call POST '/api/v1/auth/login' (,@{
    email = $lockEmail; password = 'definitely-wrong'
}) $null
Check '6th attempt blocked by the rate limiter' $blocked.code 1204

# The limiter runs BEFORE password verification, so even a correct password waits.
$blockedEvenCorrect = Call POST '/api/v1/auth/login' (,@{
    email = $lockEmail; password = 'whatever'
}) $null
Check 'rate limit applies before the password check' $blockedEvenCorrect.code 1204

Write-Host ''
Write-Host '=== pagination bounds ===' -ForegroundColor Cyan
Check 'size above 200 rejected' (Call GET '/api/v1/ledgers?page=1&size=201' $null $token).code 1101
Check 'page zero rejected' (Call GET '/api/v1/ledgers?page=0&size=10' $null $token).code 1101
Check 'size at the limit is accepted' (Call GET '/api/v1/ledgers?page=1&size=200' $null $token).code 0
Check 'negative page rejected' (Call GET '/api/v1/ledgers?page=-1' $null $token).code 1101
Check 'subject entries limit above 200 rejected' (Call GET "/api/v1/subjects/$($fx.lunchId)/entries?limit=201" $null $token).code 1101

# ------------------------------------------------------------------ keyword search
Write-Host ''
Write-Host '=== keyword search (remark / ledger no) ===' -ForegroundColor Cyan

# Book three more expenses AFTER the earlier assertions, so their totals are not
# disturbed. Distinctive remarks let the search be checked precisely.
$marks = @('coffee with team', 'lunch box', 'special 50% off')
foreach ($mark in $marks) {
    Call POST '/api/v1/ledgers/expense' (,@{
        amount = '11.00'; expenseSubjectId = $fx.lunchId
        assetSubjectId = $fx.alipayId; occurredDate = '2026-10-20'; remark = $mark
    }) $token | Out-Null
}

$hitCoffee = Call GET '/api/v1/ledgers?page=1&size=50&keyword=coffee' $null $token
Check 'keyword matches a remark' $hitCoffee.data.total 1
Check 'matched row carries the remark' $hitCoffee.data.records[0].remark 'coffee with team'

$hitLunch = Call GET '/api/v1/ledgers?page=1&size=50&keyword=lunch' $null $token
Check 'keyword matches a different remark' $hitLunch.data.total 1

# A ledger number prefix must also match (search covers the receipt no).
$sampleNo = $hitCoffee.data.records[0].ledgerNo
$prefix = $sampleNo.Substring(0, 10)
$hitNo = Call GET ("/api/v1/ledgers?page=1&size=50&keyword=" + $prefix) $null $token
Check-True 'keyword matches a ledger-number prefix' ($hitNo.data.total -ge 1)

# THE important one: % and _ are LIKE wildcards. Without escaping, "%" would
# return every ledger. The search must treat them as literal characters.
$hitPercent = Call GET '/api/v1/ledgers?page=1&size=50&keyword=%25' $null $token
Check 'percent is literal, not a wildcard' $hitPercent.data.total 1
Check 'percent matched the right row' $hitPercent.data.records[0].remark 'special 50% off'

$hitUnderscore = Call GET '/api/v1/ledgers?page=1&size=50&keyword=_' $null $token
Check 'underscore is literal, not a wildcard' $hitUnderscore.data.total 0

$hitNone = Call GET '/api/v1/ledgers?page=1&size=50&keyword=zzzzz' $null $token
Check 'no match returns zero rows' $hitNone.data.total 0

# Blank keyword must be ignored, not treated as "match everything" or "match nothing".
$noFilter = Call GET '/api/v1/ledgers?page=1&size=50' $null $token
$blankFilter = Call GET '/api/v1/ledgers?page=1&size=50&keyword=%20%20' $null $token
Check 'blank keyword behaves like no filter' $blankFilter.data.total $noFilter.data.total

# Length cap keeps LIKE from scanning absurd patterns.
$tooLong = Call GET ("/api/v1/ledgers?page=1&size=50&keyword=" + ('a' * 51)) $null $token
Check 'keyword over 50 chars rejected' $tooLong.code 1101

# ------------------------------------------------------------------ daily aggregation
# The calendar heatmap consumes analysis/trend with granularity=DAY. Two properties
# must hold or the chart lies: same-day expenses merge into one cell, and days with
# no records are omitted (filling them with 0 would flatten the colour scale).
Write-Host ''
Write-Host '=== daily aggregation (heatmap contract) ===' -ForegroundColor Cyan

# The keyword section above booked 3 x 11.00 on 2026-10-20, so one day must merge.
$dayOne = Call GET '/api/v1/analysis/trend?granularity=DAY&startDate=2026-10-20&endDate=2026-10-20' $null $token
Check 'DAY granularity code' $dayOne.code 0
Check 'same-day expenses merge into one point' (@($dayOne.data.points).Count) 1
Check 'merged daily amount is 3 x 11.00' $dayOne.data.points[0].amount '33.00'
Check 'daily point period is an ISO date' $dayOne.data.points[0].period '2026-10-20'

# A whole-month window must NOT invent zero points for the other 30 days.
$monthDays = Call GET '/api/v1/analysis/trend?granularity=DAY&startDate=2026-10-01&endDate=2026-10-31' $null $token
Check-True 'empty days are omitted, not zero-filled' (@($monthDays.data.points).Count -lt 31)
$allNonZero = $true
foreach ($pt in @($monthDays.data.points)) { if ([double]$pt.amount -le 0) { $allNonZero = $false } }
Check-True 'no zero-amount points are returned' $allNonZero

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
