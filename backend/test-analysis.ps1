<#
.SYNOPSIS
    Consumption analysis acceptance tests (AC-10, AC-11) plus endpoint coverage.

.DESCRIPTION
    Covers the criteria that depend on the analysis module:

      AC-10  period-over-period comparison -- matches hand-computed values, and
             returns rateAvailable=false when the base period is 0
      AC-11  contribution -- the identity sum(diff_i) = totalDiff holds

    Plus: overview, trend, category-share (with level semantics), period-report,
    and the zero-base / negative-base edge cases from the design doc section 7.6.3.

    Conventions (design doc section 6.1.4):
      * business errors are HTTP 200 + a non-zero `code`
      * money and rates are strings; a rate is NULL (not "0.00", not "Infinity")
        when it is mathematically meaningless -- e.g. base = 0

    NOTE: pure ASCII only (PowerShell 5.1 reads .ps1 with the ANSI code page).
    Descriptive variable names only: single letters collide with built-in
    aliases (fl, q, ...) and silently produce empty values.

    Subject codes: 100104 ALIPAY, 50010102 LUNCH, 50010202 TAXI

    Run the backend first, then:  .\test-analysis.ps1
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
        return @{ http = 0; ok = $false; code = -1; message = "transport: $_"; data = $null }
    } finally {
        $req.Dispose()
    }
    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        try { $parsed = $text | ConvertFrom-Json } catch { }
    }
    return @{
        http    = $status
        ok      = ($status -lt 400)
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

function Check-Rejected([string]$label, $result, $expectedCode) {
    $rejected = ($result.ok -and $result.code -ne 0)
    Check $label $rejected $true
    if ($rejected) {
        Write-Host ("        code={0} msg={1}" -f $result.code, $result.message) -ForegroundColor DarkGray
    }
    if ($expectedCode) { Check "$label (code)" $result.code $expectedCode }
}

# ---------------------------------------------------------------------------
# Fixture. Produces a user whose spending is known exactly:
#
#   2026-09    lunch 100.00 + taxi 50.00  = 150.00
#   2026-10    lunch 200.00 + taxi 30.00  = 230.00
#   2025-10    (nothing)                  -> base = 0 for the YoY check
#
#   MoM: diff = 230 - 150 =  80.00, rate = 80/150 = 53.33%
#   YoY: diff = 230 -   0 = 230.00, rate = null (base = 0)
#   Contribution: lunch +100.00, taxi -20.00, total +80.00
# ---------------------------------------------------------------------------
function New-AnalysisFixture([string]$tag) {
    $stamp = Get-Date -Format 'HHmmssfff'
    $reg = Call POST '/api/v1/auth/register' (,@{
        email = "$tag$stamp@example.com"; password = 'test12345'; nickname = $tag
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
        @{ amount = '100.00'; subjectId = $lunch.id; date = '2026-09-05' },
        @{ amount = '50.00';  subjectId = $taxi.id;  date = '2026-09-10' },
        @{ amount = '200.00'; subjectId = $lunch.id; date = '2026-10-05' },
        @{ amount = '30.00';  subjectId = $taxi.id;  date = '2026-10-08' }
    )
    foreach ($b in $bookings) {
        $r = Call POST '/api/v1/ledgers/expense' (,@{
            amount = $b.amount; expenseSubjectId = $b.subjectId
            assetSubjectId = $alipay.id; occurredDate = $b.date
        }) $token
        if ($r.code -ne 0) { throw "booking failed: $($r.code) $($r.message)" }
    }

    return @{ token = $token; alipayId = $alipay.id; lunchId = $lunch.id; taxiId = $taxi.id }
}

$user = New-AnalysisFixture 'anaA'
$token = $user.token
Write-Host ''
Write-Host 'Fixture: 2026-09 = 150.00 (lunch 100 + taxi 50), 2026-10 = 230.00 (lunch 200 + taxi 30)' -ForegroundColor DarkGray

# ------------------------------------------------------------------ overview
Write-Host ''
Write-Host '=== overview / period-report ===' -ForegroundColor Cyan

$oct = Call GET '/api/v1/analysis/overview?startDate=2026-10-01&endDate=2026-10-31' $null $token
Check 'overview code' $oct.code 0
Check 'overview expense' $oct.data.expense '230.00'
Check 'overview income' $oct.data.income '0.00'
Check 'overview balance = income - expense' $oct.data.balance '-230.00'

$sep = Call GET '/api/v1/analysis/overview?startDate=2026-09-01&endDate=2026-09-30' $null $token
Check 'September expense' $sep.data.expense '150.00'

$report = Call GET '/api/v1/analysis/period-report?granularity=MONTH&period=2026-10' $null $token
Check 'period-report matches overview' $report.data.expense '230.00'
Check 'period-report window start' $report.data.startDate '2026-10-01'
Check 'period-report window end' $report.data.endDate '2026-10-31'

$badRange = Call GET '/api/v1/analysis/overview?startDate=2026-10-31&endDate=2026-10-01' $null $token
Check-Rejected 'reversed date range refused' $badRange 1103

# ------------------------------------------------------------------ trend
Write-Host ''
Write-Host '=== trend ===' -ForegroundColor Cyan

$trend = Call GET '/api/v1/analysis/trend?granularity=MONTH&startDate=2026-09-01&endDate=2026-10-31' $null $token
Check 'trend code' $trend.code 0
Check 'trend point count' (@($trend.data.points).Count) 2
Check 'trend first period' $trend.data.points[0].period '2026-09'
Check 'trend first amount' $trend.data.points[0].amount '150.00'
Check 'trend second amount' $trend.data.points[1].amount '230.00'
Check 'trend total' $trend.data.total '380.00'
# A month with no bookings must NOT be invented as a zero point. The fixture
# only books in 2026-09 and 2026-10, so a ten-month window must still yield
# exactly two points -- not ten with eight zeros.
$dense = Call GET '/api/v1/analysis/trend?granularity=MONTH&startDate=2026-01-01&endDate=2026-10-31' $null $token
Check 'trend omits empty months rather than inventing zeros' (@($dense.data.points).Count) 2

# ------------------------------------------------------------------ AC-10
Write-Host ''
Write-Host '=== AC-10: MoM / YoY (base period zero -> rateAvailable=false) ===' -ForegroundColor Cyan

$cmp = Call GET '/api/v1/analysis/compare?granularity=MONTH&period=2026-10&alignWindow=false' $null $token
Check 'compare code' $cmp.code 0
Check 'AC-10 current amount' $cmp.data.currentAmount '230.00'
Check 'AC-10 MoM base period' $cmp.data.mom.period '2026-09'
Check 'AC-10 MoM base amount' $cmp.data.mom.amount '150.00'
Check 'AC-10 MoM diff' $cmp.data.mom.diff '80.00'
Check 'AC-10 MoM rate = 80/150' $cmp.data.mom.rate '53.33'
Check 'AC-10 MoM rateAvailable' $cmp.data.mom.rateAvailable $true
Check 'AC-10 YoY base period' $cmp.data.yoy.period '2025-10'
Check 'AC-10 YoY base amount is zero' $cmp.data.yoy.amount '0.00'
Check 'AC-10 YoY diff' $cmp.data.yoy.diff '230.00'
# The heart of AC-10: a zero base must NOT report 100% or infinity.
Check 'AC-10 YoY rate is null when base = 0' $cmp.data.yoy.rate $null
Check 'AC-10 YoY rateAvailable is false' $cmp.data.yoy.rateAvailable $false
Check-True 'AC-10 YoY carries an explanatory note' ($null -ne $cmp.data.yoy.note)

# Negative base period is NOT reachable through the API, so it is not tested
# end-to-end here. Reasoning, recorded so nobody wastes time re-deriving it:
#
#   Net expense for a period = SUM(expense debits) - SUM(expense credits).
#   The only source of expense credits is reversal entries, and
#   LedgerCoreService.reverse refuses to reverse more than the original amount
#   (REVERSAL_AMOUNT_EXCEEDED, 1404). Therefore credits can never exceed debits,
#   so a period's net expense is structurally >= 0.
#
# The service still handles a negative base defensively (returns rate = null +
# an explanatory note instead of a nonsense growth rate), because the guard costs
# one branch and silently emitting a wrong percentage would be worse. The
# zero-base case -- which IS reachable and IS what AC-10 names -- is asserted above.
# ------------------------------------------------------------------ AC-11
Write-Host ''
Write-Host '=== AC-11: contribution identity sum(diff_i) = totalDiff ===' -ForegroundColor Cyan

$contrib = Call GET '/api/v1/analysis/contribution?granularity=MONTH&period=2026-10&basePeriod=2026-09' $null $token
Check 'contribution code' $contrib.code 0
Check 'AC-11 total current' $contrib.data.totalCurrent '230.00'
Check 'AC-11 total base' $contrib.data.totalBase '150.00'
Check 'AC-11 total diff' $contrib.data.totalDiff '80.00'
Check 'AC-11 total diff rate' $contrib.data.totalDiffRate '53.33'

# Identity check, computed from the response itself.
$sumOfDiffs = 0.0
foreach ($c in $contrib.data.contributors) { $sumOfDiffs += [double]$c.diff }
$sumOfDiffs += [double]$contrib.data.othersDiff
Check 'AC-11 sum(diff_i) + othersDiff equals totalDiff' $sumOfDiffs.ToString('0.00') '80.00'

$lunchRow = $contrib.data.contributors | Where-Object { $_.subjectId -eq $user.lunchId } | Select-Object -First 1
$taxiRow  = $contrib.data.contributors | Where-Object { $_.subjectId -eq $user.taxiId }  | Select-Object -First 1
Check 'AC-11 lunch current' $lunchRow.current '200.00'
Check 'AC-11 lunch base' $lunchRow.base '100.00'
Check 'AC-11 lunch diff' $lunchRow.diff '100.00'
# 100/80 = 125% -- a category can contribute more than 100% when another offsets it.
Check 'AC-11 lunch contribution rate' $lunchRow.contributionRate '125.00'
Check 'AC-11 taxi diff is negative (it saved money)' $taxiRow.diff '-20.00'
Check 'AC-11 taxi contribution rate' $taxiRow.contributionRate '-25.00'

# ------------------------------------------------------------------ share
Write-Host ''
Write-Host '=== category-share: level counts user-facing tiers, not tree depth ===' -ForegroundColor Cyan

$share1 = Call GET '/api/v1/analysis/category-share?startDate=2026-10-01&endDate=2026-10-31&level=1' $null $token
Check 'share code' $share1.code 0
Check 'share total' $share1.data.total '230.00'
Check 'share level-1 item count' (@($share1.data.items).Count) 2
# level=1 must be the category nodes (tree depth 2), NOT the expense type
# container at tree depth 1 -- the latter would yield a single 100% slice.
# Assert by subject id, which is stable and keeps this file pure ASCII.
$idsAtLevel1 = @($share1.data.items | ForEach-Object { $_.subjectId })
$parentOfLunch = ($share1.data.items | Where-Object { $_.subjectId -eq $user.lunchId })
Check-True 'level=1 returns exactly the two category nodes' ($idsAtLevel1.Count -eq 2)
Check-True 'level=1 does not return a single type container' ($idsAtLevel1.Count -gt 1)

$shareSum = 0.0
foreach ($i in $share1.data.items) { $shareSum += [double]$i.share }
Check 'shares sum to exactly 100.00' $shareSum.ToString('0.00') '100.00'
$foodRow = $share1.data.items | Sort-Object { -[double]$_.amount } | Select-Object -First 1
Check 'largest share amount' $foodRow.amount '200.00'
Check 'largest share percentage' $foodRow.share '86.96'

$share2 = Call GET '/api/v1/analysis/category-share?startDate=2026-10-01&endDate=2026-10-31&level=2' $null $token
$idsAtLevel2 = @($share2.data.items | ForEach-Object { $_.subjectId })
Check-True 'level=2 drills down to the leaf subjects' ($idsAtLevel2.Count -eq 2)
Check-True 'level=2 includes the lunch leaf' ($idsAtLevel2 -contains $user.lunchId)
Check-True 'level=2 includes the taxi leaf' ($idsAtLevel2 -contains $user.taxiId)

# Empty window: shares must be 0.00, not NaN or a division error.
$empty = Call GET '/api/v1/analysis/category-share?startDate=2020-01-01&endDate=2020-01-31&level=1' $null $token
Check 'empty-window share code' $empty.code 0
Check 'empty-window total' $empty.data.total '0.00'
Check 'empty-window item count' (@($empty.data.items).Count) 0

# ------------------------------------------------------------------ isolation
Write-Host ''
Write-Host '=== cross-user isolation ===' -ForegroundColor Cyan

$other = New-AnalysisFixture 'anaB'
$otherTrend = Call GET '/api/v1/analysis/trend?granularity=MONTH&startDate=2026-09-01&endDate=2026-10-31' $null $other.token
Check 'other user sees only own data (same fixture, so same total)' $otherTrend.data.total '380.00'
$mine = Call GET '/api/v1/analysis/trend?granularity=MONTH&startDate=2026-09-01&endDate=2026-10-31' $null $token
Check 'user A total unaffected by user B' $mine.data.total '380.00'
# Subject-scoped query with another user's subject must be refused.
$foreign = Call GET "/api/v1/analysis/trend?granularity=MONTH&startDate=2026-09-01&endDate=2026-10-31&subjectId=$($other.lunchId)" $null $token
Check-Rejected 'analysis refuses another user subjectId' $foreign 1301

# ------------------------------------------------------------------ export
Write-Host ''
Write-Host '=== export (CSV) ===' -ForegroundColor Cyan

# The export endpoint is the one non-JSON response in this module, so it needs
# its own request path: it returns text/csv with a UTF-8 BOM, not a Result body.
function Get-Csv([string]$path, [string]$tok) {
    $req = New-Object System.Net.Http.HttpRequestMessage(
        [System.Net.Http.HttpMethod]::new('GET'), "$BaseUrl$path")
    $req.Headers.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $tok)
    $resp = $script:http.SendAsync($req).GetAwaiter().GetResult()
    $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $status = [int]$resp.StatusCode
    $ctype = $resp.Content.Headers.ContentType.ToString()
    $disp = if ($resp.Content.Headers.ContentDisposition) { $resp.Content.Headers.ContentDisposition.ToString() } else { '' }
    $resp.Dispose(); $req.Dispose()
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    return @{ http = $status; ctype = $ctype; disp = $disp; text = $text; bom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) }
}

$csvCat = Get-Csv '/api/v1/analysis/export?type=category&startDate=2026-10-01&endDate=2026-10-31' $token
Check 'export category HTTP' $csvCat.http 200
Check 'export is text/csv' ($csvCat.ctype -like 'text/csv*') $true
Check 'export sends a UTF-8 BOM (Excel needs it)' $csvCat.bom $true
Check 'export sets attachment filename' ($csvCat.disp -like '*analysis-category.csv*') $true
$csvLines = $csvCat.text -split "`r?`n" | Where-Object { $_ -ne '' }
# Header text is Chinese, and this file must stay pure ASCII (see the top note),
# so expected values are built from character codes.
#   header words -> codepoints:
#     category header: U+5206 U+7C7B (category), U+91D1 U+989D (amount), U+5360 U+6BD4 (share)
#     trend header   : U+671F U+95F4 (period), U+91D1 U+989D (amount)
#     totals label   : U+5408 U+8BA1 (total)
$u = { param([int[]]$c) -join ($c | ForEach-Object { [char]$_ }) }
$HDR_CAT   = (& $u 0x5206,0x7C7B) + ',' + (& $u 0x91D1,0x989D) + ',' + (& $u 0x5360,0x6BD4) + '(%)'
$HDR_TREND = (& $u 0x671F,0x95F4) + ',' + (& $u 0x91D1,0x989D)
$TOTAL     = (& $u 0x5408,0x8BA1)

Check 'export category header row' $csvLines[0] $HDR_CAT
Check-True 'export category has a data row' ($csvLines.Count -ge 3)
Check 'export category totals row' $csvLines[-1] ($TOTAL + ',230.00,')

$csvTrend = Get-Csv '/api/v1/analysis/export?type=trend&granularity=MONTH&startDate=2026-09-01&endDate=2026-10-31' $token
Check 'export trend HTTP' $csvTrend.http 200
$trendLines = $csvTrend.text -split "`r?`n" | Where-Object { $_ -ne '' }
Check 'export trend header row' $trendLines[0] $HDR_TREND
Check 'export trend totals row' $trendLines[-1] ($TOTAL + ',380.00')

# CSV escaping: a field containing a comma must be quoted, or the row gains a column.
# The preset tree has no comma-named subject, so assert the helper indirectly by
# checking that every data row has the expected column count.
$colCounts = $csvCat.text -split "`r?`n" | Where-Object { $_ -ne '' } | ForEach-Object { ($_.Split(',')).Count }
Check-True 'export rows keep a stable column count' (($colCounts | Select-Object -Unique).Count -eq 1)

# ------------------------------------------------------------------ guards
Write-Host ''
Write-Host '=== guards ===' -ForegroundColor Cyan

$badGran = Call GET '/api/v1/analysis/trend?granularity=DECADE' $null $token
Check-Rejected 'unsupported granularity refused' $badGran 1101
$badPeriod = Call GET '/api/v1/analysis/compare?granularity=MONTH&period=not-a-month' $null $token
Check-True 'malformed period rejected (any non-zero code or 4xx)' (($badPeriod.code -ne 0) -or ($badPeriod.http -ge 400))

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
