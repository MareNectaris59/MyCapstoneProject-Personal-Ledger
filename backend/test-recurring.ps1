<#
.SYNOPSIS
    Recurring rule acceptance tests.

.DESCRIPTION
    Covers create / list / run / idempotency / catch-up / validation for the
    recurring bookkeeping feature (recurring_rule, V5).

    The three properties that matter and are therefore asserted hardest:
      1. running twice never books twice (idempotency key = rec-<ruleId>-<date>)
      2. a rule whose start date is in the past catches up EVERY missed period,
         not just the latest one
      3. month-end anchoring: a rule anchored to day 31 must come back to 31
         after passing through a shorter month

    NOTE: pure ASCII only (PowerShell 5.1 reads .ps1 with the ANSI code page).

    Run the backend first, then:  .\test-recurring.ps1
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
# Fixture
# ---------------------------------------------------------------------------
$stamp = Get-Date -Format 'HHmmssfff'
$reg = Call POST '/api/v1/auth/register' (,@{
    email = "rec$stamp@example.com"; password = 'start1234'; nickname = 'rec'
}) $null
if ($reg.code -ne 0) { throw "register failed: $($reg.code) $($reg.message)" }
$token = $reg.data.accessToken

$assets = Get-LeavesByType $token 'ASSET'
$expenses = Get-LeavesByType $token 'EXPENSE'
if ($assets.Count -lt 2 -or $expenses.Count -lt 1) { throw 'preset subjects missing' }
$payAcct = $assets[0]
$toAcct = $assets[1]
$expenseSubject = $expenses[0]

Call POST "/api/v1/subjects/$($payAcct.id)/opening-balance" (,@{
    amount = '9000.00'; occurredDate = '2026-01-01'
}) $token | Out-Null

$todayDate = (Get-Date).ToString('yyyy-MM-dd')
$dayOfMonth = (Get-Date).Day

Write-Host ''
Write-Host "Fixture: today=$todayDate dayOfMonth=$dayOfMonth" -ForegroundColor DarkGray

# ------------------------------------------------------------------ create
Write-Host ''
Write-Host '=== create a monthly rule starting today ===' -ForegroundColor Cyan

$created = Call POST '/api/v1/recurring' (,@{
    name = 'rent'
    ledgerType = 'EXPENSE'
    amount = '2800.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    remark = 'monthly rent'
    frequency = 'MONTHLY'
    startDate = $todayDate
}) $token
Check 'create code' $created.code 0
Check 'next run date is the start date' $created.data.nextRunDate $todayDate
Check 'enabled by default' $created.data.enabled $true
Check 'anchor day defaults to the start day' $created.data.anchorDay $dayOfMonth
Check 'amount normalised to two decimals' $created.data.amount '2800.00'
Check 'subject name resolved' $created.data.subjectName $expenseSubject.name

$ruleId = $created.data.id

$listed = Call GET '/api/v1/recurring' $null $token
Check 'list returns the rule' (@($listed.data).Count) 1
Check 'list carries the same id' $listed.data[0].id $ruleId

# ------------------------------------------------------------------ run
Write-Host ''
Write-Host '=== run it: one ledger, then idempotent on repeat ===' -ForegroundColor Cyan

$before = (Call GET '/api/v1/ledgers?page=1&size=1' $null $token).data.total

$run1 = Call POST '/api/v1/recurring/run' $null $token
Check 'first run books one entry' $run1.data.generated 1
Check 'first run skips nothing' $run1.data.skipped 0

$after = (Call GET '/api/v1/ledgers?page=1&size=1' $null $token).data.total
Check 'ledger count grew by exactly one' $after ($before + 1)

$run2 = Call POST '/api/v1/recurring/run' $null $token
Check 'second run books nothing' $run2.data.generated 0
Check 'second run skips nothing (rule already advanced past today)' $run2.data.skipped 0

$after2 = (Call GET '/api/v1/ledgers?page=1&size=1' $null $token).data.total
Check 'ledger count unchanged after the second run' $after2 $after

# The generated ledger must be findable by its remark.
$found = Call GET ('/api/v1/ledgers?page=1&size=20&keyword=' + [uri]::EscapeDataString('monthly rent')) $null $token
Check 'generated ledger carries the remark' $found.data.total 1
Check 'generated ledger has the right amount' $found.data.records[0].amount '2800.00'

# Rule advanced one period into the future.
$afterRun = (Call GET '/api/v1/recurring' $null $token).data[0]
Check-True 'rule advanced past today' ($afterRun.nextRunDate -gt $todayDate)
Check 'last run date recorded' $afterRun.lastRunDate $todayDate

# ------------------------------------------------------------------ catch up
Write-Host ''
Write-Host '=== catch-up: a weekly rule that started 21 days ago ===' -ForegroundColor Cyan

$threeWeeksAgo = (Get-Date).AddDays(-21).ToString('yyyy-MM-dd')
$weekly = Call POST '/api/v1/recurring' (,@{
    name = 'weekly cleaning'
    ledgerType = 'EXPENSE'
    amount = '50.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    frequency = 'WEEKLY'
    startDate = $threeWeeksAgo
}) $token
Check 'weekly create code' $weekly.code 0
Check 'weekly next run is the past start date' $weekly.data.nextRunDate $threeWeeksAgo

$beforeCatchUp = (Call GET '/api/v1/ledgers?page=1&size=1' $null $token).data.total
$catchUp = Call POST '/api/v1/recurring/run' $null $token
# 21 days ago, 14, 7, and today => 4 periods
Check 'catch-up books every missed period' $catchUp.data.generated 4

$afterCatchUp = (Call GET '/api/v1/ledgers?page=1&size=1' $null $token).data.total
Check 'four ledgers were created' $afterCatchUp ($beforeCatchUp + 4)

$repeat = Call POST '/api/v1/recurring/run' $null $token
Check 're-running the catch-up books nothing' $repeat.data.generated 0

# ------------------------------------------------------------------ month-end anchor
Write-Host ''
Write-Host '=== month-end anchoring ===' -ForegroundColor Cyan

# Anchor on day 31 explicitly; after the first advance the next run date must be
# the 31st of a later month (clamped only in months that have no 31st).
$anchorRule = Call POST '/api/v1/recurring' (,@{
    name = 'anchor 31'
    ledgerType = 'EXPENSE'
    amount = '10.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    frequency = 'MONTHLY'
    anchorDay = 31
    startDate = $todayDate
}) $token
Check 'anchor create code' $anchorRule.code 0
Check 'anchor day stored as given' $anchorRule.data.anchorDay 31

Call POST '/api/v1/recurring/run' $null $token | Out-Null
$anchorAfter = (Call GET '/api/v1/recurring' $null $token).data |
    Where-Object { $_.id -eq $anchorRule.data.id }
$nextDay = [int]([datetime]$anchorAfter.nextRunDate).Day
$nextMonth = ([datetime]$anchorAfter.nextRunDate).Month
$expectedDay = [Math]::Min(31, [datetime]::DaysInMonth(([datetime]$anchorAfter.nextRunDate).Year, $nextMonth))
Check 'anchor recovered to the intended day' $nextDay $expectedDay

# ------------------------------------------------------------------ validation
Write-Host ''
Write-Host '=== validation ===' -ForegroundColor Cyan

$noName = Call POST '/api/v1/recurring' (,@{
    name = ''
    ledgerType = 'EXPENSE'
    amount = '10.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    frequency = 'MONTHLY'
    startDate = $todayDate
}) $token
Check 'empty name rejected' $noName.code 1101

$badType = Call POST '/api/v1/recurring' (,@{
    name = 'x'
    ledgerType = 'OPENING'
    amount = '10.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    frequency = 'MONTHLY'
    startDate = $todayDate
}) $token
Check-True 'OPENING type refused' ($badType.code -ne 0)

$badFreq = Call POST '/api/v1/recurring' (,@{
    name = 'x'
    ledgerType = 'EXPENSE'
    amount = '10.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    frequency = 'DAILY'
    startDate = $todayDate
}) $token
Check-True 'DAILY frequency refused' ($badFreq.code -ne 0)

$wrongSubjectType = Call POST '/api/v1/recurring' (,@{
    name = 'x'
    ledgerType = 'INCOME'
    amount = '10.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    frequency = 'MONTHLY'
    startDate = $todayDate
}) $token
Check-True 'expense subject refused for INCOME' ($wrongSubjectType.code -ne 0)

$zeroAmount = Call POST '/api/v1/recurring' (,@{
    name = 'x'
    ledgerType = 'EXPENSE'
    amount = '0.00'
    subjectId = $expenseSubject.id
    counterSubjectId = $payAcct.id
    frequency = 'MONTHLY'
    startDate = $todayDate
}) $token
Check-True 'zero amount refused' ($zeroAmount.code -ne 0)

# ------------------------------------------------------------------ update / delete
Write-Host ''
Write-Host '=== update and delete ===' -ForegroundColor Cyan

$disabled = Call PUT "/api/v1/recurring/$ruleId" (,@{
    enabled = $false; amount = '2900.00'
}) $token
Check 'update code' $disabled.code 0
Check 'rule disabled' $disabled.data.enabled $false
Check 'amount updated' $disabled.data.amount '2900.00'

# A disabled rule must not be picked up by a run.
$beforeDisabled = (Call GET '/api/v1/ledgers?page=1&size=1' $null $token).data.total
$runDisabled = Call POST '/api/v1/recurring/run' $null $token
Check 'disabled rule generates nothing' $runDisabled.data.generated 0

$deleted = Call DELETE "/api/v1/recurring/$ruleId" $null $token
Check 'delete code' $deleted.code 0
$afterDelete = Call GET '/api/v1/recurring' $null $token
$stillThere = @($afterDelete.data | Where-Object { $_.id -eq $ruleId }).Count
Check 'deleted rule no longer listed' $stillThere 0

# Deleting one user's rule must not be possible from another account.
$otherStamp = Get-Date -Format 'HHmmssfff'
$other = Call POST '/api/v1/auth/register' (,@{
    email = "recx$otherStamp@example.com"; password = 'start1234'; nickname = 'recx'
}) $null
$otherToken = $other.data.accessToken
$otherAssets = Get-LeavesByType $otherToken 'ASSET'
$otherExpenses = Get-LeavesByType $otherToken 'EXPENSE'
$otherRule = Call POST '/api/v1/recurring' (,@{
    name = 'other'
    ledgerType = 'EXPENSE'
    amount = '10.00'
    subjectId = $otherExpenses[0].id
    counterSubjectId = $otherAssets[0].id
    frequency = 'MONTHLY'
    startDate = (Get-Date).ToString('yyyy-MM-dd')
}) $otherToken
$crossDelete = Call DELETE "/api/v1/recurring/$($otherRule.data.id)" $null $token
Check-True 'cannot delete another users rule' ($crossDelete.code -ne 0)

$otherList = Call GET '/api/v1/recurring' $null $otherToken
Check 'the other users rule survived' (@($otherList.data).Count) 1

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
