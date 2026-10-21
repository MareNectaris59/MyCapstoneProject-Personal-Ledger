<#
.SYNOPSIS
    Budget module acceptance tests (AC-08, AC-09) plus endpoint coverage.

.DESCRIPTION
    Covers the criteria that depend on the budget module:

      AC-08  budget execution  -- limit 1000, spent 900 -> execution rate 90.00%
      AC-09  rollover          -- previous surplus 200 + this period 1000 -> available 1200

    Plus the rest of the /api/v1/budgets surface: list, detail, periods,
    execution, update, delete, alerts, and the period-overlap guard.

    Conventions (design doc section 6.1.4):
      * business errors are HTTP 200 + non-zero `code`; 401/403/500 are non-2xx
      * money is a string ("900.00"); rates are strings too, and NULL when the
        denominator is 0 (never "0.00" -- see the design doc section 7.5.3)

    NOTE: pure ASCII only. Windows PowerShell 5.1 reads .ps1 with the system
    ANSI code page, so a Chinese literal here would break parsing.
    Also: descriptive variable names only -- single letters collide with
    built-in aliases (fl, q, ...) and silently produce empty values.

    Subject codes: 100104 ALIPAY, 50010102 LUNCH (leaf expense)

    Run the backend first, then:  .\test-budget.ps1
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
    $got = "$actual"
    $want = "$expected"

    # Compatibility shim between Windows PowerShell 5.1 and PowerShell 7.
    #
    # 5.1 (what this was developed on) keeps JSON date strings as strings.
    # PowerShell 7 -- which is what CI uses on Linux -- may hand back a [datetime],
    # so "$actual" renders in the machine locale ("10/05/2026 00:00:00") instead of
    # the wire format "2026-10-05", and every date assertion fails for a reason
    # that has nothing to do with the application.
    if ($got -ne $want) {
        $left = [datetime]::MinValue
        $right = [datetime]::MinValue
        if ([datetime]::TryParse($got, [ref]$left) -and
            [datetime]::TryParse($want, [ref]$right) -and
            $left -eq $right) {
            $got = $want
        }
    }

    if ($got -eq $want) {
        Write-Host ("  [PASS] {0} = {1}" -f $label, $got) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [FAIL] {0} = {1}  (expected {2})" -f $label, $got, $want) -ForegroundColor Red
        # GitHub Actions annotation. CI cannot show the raw log to a program,
        # but annotations are rendered on the run page -- so a failing assertion
        # names itself without anyone opening the log.
        Write-Host "::error title=$label::$label = $got (expected $want)"
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

# Returns @{ token; alipayId; lunchId } for a fresh user with funded alipay.
function New-FundedUser([string]$tag) {
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
    if (-not $alipay -or -not $lunch) { throw 'preset subject lookup failed' }

    Call POST "/api/v1/subjects/$($alipay.id)/opening-balance" (,@{
        amount = '10000.00'; occurredDate = '2026-01-01'
    }) $token | Out-Null

    return @{ token = $token; alipayId = $alipay.id; lunchId = $lunch.id }
}

# ------------------------------------------------------------------ AC-08
Write-Host ''
Write-Host '=== AC-08: execution rate -- limit 1000.00, spent 900.00 -> 90.00% ===' -ForegroundColor Cyan

$userA = New-FundedUser 'budA'
$monthStart = (Get-Date -Format 'yyyy-MM-01')

$created = Call POST '/api/v1/budgets' (,@{
    name = 'AC08 total'; scopeType = 'TOTAL'; periodType = 'MONTH'
    startDate = $monthStart; amount = 1000.00
}) $userA.token
Check 'create budget code' $created.code 0
Check 'default rollover mode is SURPLUS_ONLY' $created.data.rolloverMode 'SURPLUS_ONLY'
Check 'default alert threshold' $created.data.alertThreshold '80.00'
Check-True 'period rows generated' (@($created.data.totalPeriods)[0] -ge 1)

$budgetId = $created.data.id
$currentPeriod = $created.data.period
Check-True 'current period resolved' ($null -ne $currentPeriod)
Check 'fresh period used amount' $currentPeriod.usedAmount '0.00'
Check 'fresh period execution rate' $currentPeriod.executionRate '0.00'

# Book 900.00 inside the current window.
$expense = Call POST '/api/v1/ledgers/expense' (,@{
    amount = '900.00'; expenseSubjectId = $userA.lunchId
    assetSubjectId = $userA.alipayId; occurredDate = $currentPeriod.startDate
}) $userA.token
Check 'expense accepted' $expense.code 0

$execution = Call GET "/api/v1/budgets/$budgetId/execution" $null $userA.token
Check 'AC-08 used amount' $execution.data.current.usedAmount '900.00'
Check 'AC-08 available amount' $execution.data.current.availableAmount '1000.00'
Check 'AC-08 remaining' $execution.data.current.remaining '100.00'
Check 'AC-08 execution rate is exactly 90.00' $execution.data.current.executionRate '90.00'

# 90% crosses the default 80% threshold, so an alert must exist.
#
# IMPORTANT: assert this NOW, before any budget update. A later step raises the
# limit to 2000, which drops the rate to 45% -- below the threshold -- and since
# the alert row is only ever created (never removed), reading alerts after that
# point would still find it, but the *rate* assertions afterwards would be
# meaningless. Keeping the alert check here keeps each assertion about one thing.
Check-True 'AC-08 warning raised at 80% threshold' ($execution.data.alerts.Count -ge 1)
$alertLevel = $execution.data.alerts[0].alertLevel
Write-Host ("        alert level = {0}" -f $alertLevel) -ForegroundColor DarkGray

# The standalone alerts endpoint must agree with /execution.
$alertsForA = Call GET '/api/v1/budgets/alerts' $null $userA.token
Check-True 'AC-08 alerts endpoint agrees with execution view' (@($alertsForA.data).Count -ge 1)

# ------------------------------------------------------------------ AC-09
Write-Host ''
Write-Host '=== AC-09: rollover -- previous surplus 200.00 -> this period available 1200.00 ===' -ForegroundColor Cyan

$userB = New-FundedUser 'budB'
$lastMonthStart = (Get-Date).AddMonths(-1).ToString('yyyy-MM-01')

$rolloverBudget = Call POST '/api/v1/budgets' (,@{
    name = 'AC09 rollover'; scopeType = 'TOTAL'; periodType = 'MONTH'
    startDate = $lastMonthStart; amount = 1000.00; rolloverMode = 'SURPLUS_ONLY'
}) $userB.token
Check 'create rollover budget code' $rolloverBudget.code 0
$rolloverId = $rolloverBudget.data.id

$periods = (Call GET "/api/v1/budgets/$rolloverId/periods" $null $userB.token).data
Check-True 'at least two periods exist' (@($periods).Count -ge 2)
$firstPeriod = $periods[0]
$secondPeriod = $periods[1]

# Spend 800.00 in the first period -> 200.00 surplus carries into the second.
$spend = Call POST '/api/v1/ledgers/expense' (,@{
    amount = '800.00'; expenseSubjectId = $userB.lunchId
    assetSubjectId = $userB.alipayId; occurredDate = $firstPeriod.startDate
}) $userB.token
Check 'first-period expense accepted' $spend.code 0

$periodsAfter = (Call GET "/api/v1/budgets/$rolloverId/periods" $null $userB.token).data
$p1 = $periodsAfter[0]
$p2 = $periodsAfter[1]

Check 'AC-09 first period used' $p1.usedAmount '800.00'
Check 'AC-09 first period remaining' $p1.remaining '200.00'
Check 'AC-09 first period rate' $p1.executionRate '80.00'
Check 'AC-09 second period base amount' $p2.baseAmount '1000.00'
Check 'AC-09 carry-in equals previous surplus' $p2.carryIn '200.00'
Check 'AC-09 available = base + carry-in' $p2.availableAmount '1200.00'

# NONE must not carry anything.
#
# NOTE: the earlier rollover budget was created WITHOUT an endDate, so the
# overlap guard treats it as open-ended (9999-12-31) and refuses any second
# TOTAL budget. That guard is correct -- two TOTAL budgets over one window would
# double-count the same spending. So delete the earlier budget first to free the
# timeline, then create this one from the same start date.
Write-Host ''
Write-Host '  -- rolloverMode NONE must carry nothing --' -ForegroundColor DarkCyan

Call DELETE "/api/v1/budgets/$rolloverId" $null $userB.token | Out-Null

$noneBudget = Call POST '/api/v1/budgets' (,@{
    name = 'AC09 none'; scopeType = 'TOTAL'; periodType = 'MONTH'
    startDate = $lastMonthStart; amount = '500.00'; rolloverMode = 'NONE'
}) $userB.token
if ($noneBudget.code -ne 0) {
    Write-Host ("        create failed: code={0} msg={1}" -f $noneBudget.code, $noneBudget.message) -ForegroundColor Yellow
}
Check 'NONE budget created' $noneBudget.code 0
if ($noneBudget.code -eq 0) {
    $nonePeriods = (Call GET "/api/v1/budgets/$($noneBudget.data.id)/periods" $null $userB.token).data
    Call POST '/api/v1/ledgers/expense' (,@{
        amount = '100.00'; expenseSubjectId = $userB.lunchId
        assetSubjectId = $userB.alipayId; occurredDate = $nonePeriods[0].startDate
    }) $userB.token | Out-Null
    $noneAfter = (Call GET "/api/v1/budgets/$($noneBudget.data.id)/periods" $null $userB.token).data
    # A TOTAL-scope budget counts EVERY expense in its window, regardless of which
    # budget the user had in mind when booking it. User B already spent 800.00 in
    # this period (booked while the rollover budget was alive) plus the 100.00
    # booked just now, so used = 900.00 against a 500.00 limit. remaining is
    # therefore legitimately NEGATIVE -- correct behaviour, not a bug.
    Check 'NONE first period used counts all spending in window' $noneAfter[0].usedAmount '900.00'
    Check 'NONE first period remaining goes negative on overspend' $noneAfter[0].remaining '-400.00'
    Check 'NONE second period carry-in is zero' $noneAfter[1].carryIn '0.00'
    Check 'NONE second period available stays at base' $noneAfter[1].availableAmount '500.00'
}

# ------------------------------------------------------------------ endpoints
Write-Host ''
Write-Host '=== Endpoint coverage: list / detail / update / delete / alerts / guards ===' -ForegroundColor Cyan

$list = Call GET '/api/v1/budgets' $null $userA.token
Check 'list code' $list.code 0
# PowerShell's ConvertFrom-Json turns an empty JSON array into $null rather than
# an empty array, so .Count on it yields nothing. Normalise before asserting.
Check 'list size' (@($list.data).Count) 1

$detail = Call GET "/api/v1/budgets/$budgetId" $null $userA.token
Check 'detail code' $detail.code 0
Check-True 'detail carries period series' (@($detail.data.periods).Count -ge 1)

# Update: change limit to 2000 -> rate must fall to 45.00
$updated = Call PUT "/api/v1/budgets/$budgetId" (,@{ amount = 2000.00 }) $userA.token
Check 'update code' $updated.code 0
Check 'update reflected in current period available' $updated.data.period.availableAmount '2000.00'
Check 'update recomputed execution rate to 45.00' $updated.data.period.executionRate '45.00'

# Overlap guard: a second TOTAL budget covering the same window must be refused.
$overlap = Call POST '/api/v1/budgets' (,@{
    name = 'overlap'; scopeType = 'TOTAL'; periodType = 'MONTH'
    startDate = $monthStart; amount = 100.00
}) $userA.token
Check-Rejected 'overlapping budget refused' $overlap 1502

# Validation guards
$negative = Call POST '/api/v1/budgets' (,@{
    name = 'neg'; scopeType = 'TOTAL'; periodType = 'MONTH'
    startDate = '2030-01-01'; amount = -5.00
}) $userA.token
Check-Rejected 'negative amount refused' $negative 1504

$badScope = Call POST '/api/v1/budgets' (,@{
    name = 'bad'; scopeType = 'NOPE'; periodType = 'MONTH'
    startDate = '2030-01-01'; amount = 10.00
}) $userA.token
Check-Rejected 'invalid scope refused' $badScope

# A budget on an ASSET subject must be refused (only EXPENSE is budgetable).
$assetBudget = Call POST '/api/v1/budgets' (,@{
    name = 'asset'; scopeType = 'SUBJECT'; subjectId = $userA.alipayId
    periodType = 'MONTH'; startDate = '2031-01-01'; amount = 100.00
}) $userA.token
Check-Rejected 'budget on asset subject refused' $assetBudget 1503

# Cross-user isolation.
$otherUser = New-FundedUser 'budC'
$crossRead = Call GET "/api/v1/budgets/$budgetId" $null $otherUser.token
Check-Rejected 'other user cannot read the budget' $crossRead 1501
$crossUpdate = Call PUT "/api/v1/budgets/$budgetId" (,@{ amount = 1.00 }) $otherUser.token
Check-Rejected 'other user cannot update the budget' $crossUpdate 1501

# Alerts: mark read then verify the flag flips.
$alertList = Call GET '/api/v1/budgets/alerts' $null $userA.token
Check-True 'alerts listed for user A' (@($alertList.data).Count -ge 1)
if (@($alertList.data).Count -ge 1) {
    $alertId = @($alertList.data)[0].id
    $markRead = Call PUT "/api/v1/budgets/alerts/$alertId/read" $null $userA.token
    Check 'mark alert read code' $markRead.code 0
    $alertListAfter = Call GET '/api/v1/budgets/alerts' $null $userA.token
    $marked = @($alertListAfter.data) | Where-Object { $_.id -eq $alertId } | Select-Object -First 1
    Check-True 'alert flagged as read' ($marked -and $marked.read)
}

# Delete: budget disappears from the list but period rows stay.
$delete = Call DELETE "/api/v1/budgets/$budgetId" $null $userA.token
Check 'delete code' $delete.code 0
$listAfterDelete = Call GET '/api/v1/budgets' $null $userA.token
Check 'deleted budget gone from list' $listAfterDelete.data.Count 0
$goneDetail = Call GET "/api/v1/budgets/$budgetId" $null $userA.token
Check-Rejected 'deleted budget no longer readable' $goneDetail 1501

# Unknown path must be 404, not 500 (regression guard for the exception handler).
$notFound = Call GET '/api/v1/budgets/abc' $null $userA.token
Check 'unknown budget path returns 404' $notFound.http 404

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
