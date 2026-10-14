# Verify the three subject-management endpoints the redesigned UI newly uses.
# NOTE: pure ASCII (PowerShell 5.1 reads .ps1 with the ANSI code page).
# Payload hashtables are wrapped as (,@{...}) so they bind to $Body instead of
# being read as a parameter collection.

$ErrorActionPreference = 'Continue'
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
  $b = $null
  if (-not [string]::IsNullOrWhiteSpace($text)) { try { $b = $text | ConvertFrom-Json } catch {} }
  return @{ http = $status; code = $b.code; message = $b.message; data = $b.data }
}

$stamp = Get-Date -Format 'HHmmss'
$reg = Call POST '/api/v1/auth/register' (,@{ email = "ui$stamp@example.com"; password = 'ui123456' }) $null
$token = $reg.data.accessToken
Write-Host "register code=$($reg.code)  userId=$($reg.data.user.userId)"

Write-Host ''
Write-Host '=== 1. create CASH account (dashboard "+ add account") ==='
$acc = Call POST '/api/v1/subjects' (,@{ name = 'TestCash'; subjectType = 'ASSET'; accountKind = 'CASH' }) $token
Write-Host "  code=$($acc.code)  id=$($acc.data.id)  isLeaf=$($acc.data.isLeaf)"
Write-Host "  subjectCode=$($acc.data.subjectCode)  normalSide=$($acc.data.normalSide)"
$accId = $acc.data.id

Write-Host ''
Write-Host '=== 2. new account appears in /subjects/accounts ==='
$list = Call GET '/api/v1/subjects/accounts' $null $token
$found = @($list.data | Where-Object { $_.id -eq $accId }).Count
Write-Host "  accounts count=$($list.data.Count)  contains new one=$($found -eq 1)"

Write-Host ''
Write-Host '=== 3. create EXPENSE category under an existing group ==='
$tree = Call GET '/api/v1/subjects/tree?type=EXPENSE' $null $token
$group = $tree.data | Where-Object { -not $_.leaf } | Select-Object -First 1
Write-Host "  parent group: $($group.name) (id=$($group.id))"
$cat = Call POST '/api/v1/subjects' (,@{ name = 'TestCat'; subjectType = 'EXPENSE'; parentId = $group.id }) $token
Write-Host "  code=$($cat.code)  id=$($cat.data.id)  level=$($cat.data.level)  isLeaf=$($cat.data.isLeaf)"
$catId = $cat.data.id

Write-Host ''
Write-Host '=== 4. parent should no longer be a leaf ==='
$tree2 = Call GET '/api/v1/subjects/tree?type=EXPENSE' $null $token
$g2 = $tree2.data | Where-Object { $_.id -eq $group.id }
Write-Host "  parent isLeaf=$($g2.leaf)   (expect False after adding a child)"

Write-Host ''
Write-Host '=== 5. setEnabled false -> disappears from leaves ==='
$dis = Call PUT "/api/v1/subjects/$catId/enabled?enabled=false" $null $token
Write-Host "  code=$($dis.code)"
$tree3 = Call GET '/api/v1/subjects/tree?type=EXPENSE' $null $token
$stillThere = @($tree3.data | Where-Object { $_.id -eq $catId }).Count
Write-Host "  still in tree=$($stillThere -eq 1) (disabled but kept - expected)"

Write-Host ''
Write-Host '=== 6. delete the category (never used in a ledger) ==='
$del = Call DELETE "/api/v1/subjects/$catId" $null $token
Write-Host "  code=$($del.code)  msg=$($del.message)"
$tree4 = Call GET '/api/v1/subjects/tree?type=EXPENSE' $null $token
$gone = @($tree4.data | Where-Object { $_.id -eq $catId }).Count
Write-Host "  removed from tree=$($gone -eq 0)"

Write-Host ''
Write-Host '=== 7. delete the account ==='
$delAcc = Call DELETE "/api/v1/subjects/$accId" $null $token
Write-Host "  code=$($delAcc.code)  msg=$($delAcc.message)"

Write-Host ''
Write-Host '=== 8. code generation follows the preset 6-digit format ==='
Write-Host '  preset accounts are 100101..100105, so new ones must continue from 100106'
foreach ($n in 'NewAccA', 'NewAccB', 'TestAcc') {
  $x = Call POST '/api/v1/subjects' (,@{ name = $n; subjectType = 'ASSET'; accountKind = 'CASH' }) $token
  Write-Host ("    {0,-8} code={1}  subjectCode={2}  level={3}  {4}" -f $n, $x.code, $x.data.subjectCode, $x.data.level, $x.message)
}
Write-Host '  preset categories are 500101..500109, so new ones must continue from 500110'
foreach ($n in 'NewCatA', 'NewCatB') {
  $x = Call POST '/api/v1/subjects' (,@{ name = $n; subjectType = 'EXPENSE' }) $token
  Write-Host ("    {0,-8} code={1}  subjectCode={2}  level={3}  {4}" -f $n, $x.code, $x.data.subjectCode, $x.data.level, $x.message)
}

Write-Host ''
Write-Host '=== 9. new node must be a child of the type root, not a sibling ==='
$tree5 = Call GET '/api/v1/subjects/tree?type=ASSET' $null $token
Write-Host "  ASSET tree root count=$(@($tree5.data).Count)  (expect 1 - only the '资产' root)"
$exp5 = Call GET '/api/v1/subjects/tree?type=EXPENSE' $null $token
Write-Host "  EXPENSE tree root count=$(@($exp5.data).Count)  (expect 1 - only the '费用' root)"

Write-Host ''
Write-Host '=== 10. deleting a leaf that HAS ledger entries must be refused ==='
$sal = (Call GET '/api/v1/subjects/accounts' $null $token).data | Select-Object -First 1
$cat2 = Call POST '/api/v1/subjects' (,@{ name = 'TestUsed'; subjectType = 'EXPENSE' }) $token
Call POST '/api/v1/ledgers/expense' (,@{ amount = '5.00'; expenseSubjectId = $cat2.data.id; assetSubjectId = $sal.id; occurredDate = '2025-03-01' }) $token | Out-Null
Write-Host "  booked an expense against subject id=$($cat2.data.id)"
$refuse = Call DELETE "/api/v1/subjects/$($cat2.data.id)" $null $token
Write-Host "  delete -> code=$($refuse.code)  msg=$($refuse.message)"
Write-Host '  (expect 1303, i.e. refused because it is in use)'

