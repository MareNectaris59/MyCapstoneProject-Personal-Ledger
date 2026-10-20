# =============================================================================
#  Export the database into the project directory (backup / submission).
#
#  NOTE: this file must stay PURE ASCII. Windows PowerShell 5.1 reads .ps1 with
#  the system ANSI code page, so Chinese literals get mangled and break string
#  terminators, which tears the whole script apart at parse time.
#  Chinese documentation lives in docs/ (database backup notes).
#
#  Usage:
#    .\backup-db.ps1                    # full dump of personal_ledger
#    .\backup-db.ps1 -SchemaOnly        # structure only, no test data
#    .\backup-db.ps1 -Name my-snapshot  # custom file name
#    .\backup-db.ps1 -AllDatabases      # every database on the instance
#    .\backup-db.ps1 -Snapshot          # single-file SQLite copy in db\ (for submission)
#
#  Why a dump instead of relocating MySQL's data files:
#    MySQL's datadir (C:\ProgramData\MySQL\MySQL Server 8.0\Data) belongs to the
#    whole server instance, which also holds ~19 unrelated databases. Changing
#    datadir moves all of them at once, and .ibd files are useless individually -
#    they only mean something as a complete datadir. A .sql dump is the opposite:
#    one readable, commit-able file that restores on any machine.
# =============================================================================
[CmdletBinding()]
param(
    [string]$Database = 'personal_ledger',
    [string]$User     = 'root',
    [string]$Password = '123456',
    [string]$Name,
    [switch]$SchemaOnly,
    [switch]$AllDatabases,
    [switch]$Snapshot
)

$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$dumpDir = Join-Path $projectRoot 'db\dump'
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null

# ---------------------------------------------------------------- sqlite snapshot
# Produces db\<Database>.db: one file, openable with any SQLite viewer, no MySQL
# needed. It is a READ-ONLY copy for submission/demo - the running application
# keeps using MySQL. See db/tools/mysql2sqlite.py for the type mapping rules.
if ($Snapshot) {
    $python = (Get-Command python -ErrorAction SilentlyContinue).Source
    if (-not $python) { throw 'python not found on PATH (required for the SQLite snapshot)' }
    $mysqlExe = (Get-Command mysql -ErrorAction SilentlyContinue).Source
    if (-not $mysqlExe) { throw 'mysql client not found on PATH' }

    $tool = Join-Path $projectRoot 'db\tools\mysql2sqlite.py'
    if (-not (Test-Path -LiteralPath $tool)) { throw "converter not found: $tool" }

    $outDb = Join-Path $projectRoot ('db\' + $Database + '.db')

    Write-Host ''
    Write-Host 'Building SQLite snapshot...' -ForegroundColor Cyan
    Write-Host "  source : $Database (MySQL, read-only)"
    Write-Host "  output : $outDb"
    Write-Host ''

    & $python $tool --mysql $mysqlExe --user $User --password $Password `
        --database $Database --out $outDb
    if ($LASTEXITCODE -ne 0) { throw "snapshot failed (exit=$LASTEXITCODE)" }

    $item = Get-Item -LiteralPath $outDb
    Write-Host ''
    Write-Host "Done: $outDb" -ForegroundColor Green
    Write-Host "  size : $([math]::Round($item.Length / 1KB, 1)) KB"
    Write-Host ''
    Write-Host 'Open with any SQLite viewer; MySQL is not required.'
    Write-Host 'This is a READ-ONLY copy - the application still uses MySQL.'
    Write-Host ''
    exit 0
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# ---------------------------------------------------------------- build args
if ($AllDatabases) {
    $dumpArgs = @(
        "-u$User", "-p$Password",
        '--default-character-set=utf8mb4',
        '--single-transaction', '--routines', '--triggers', '--events',
        '--set-gtid-purged=OFF', '--skip-comments',
        '--all-databases',
        '--ignore-table=mysql.general_log',
        '--ignore-table=mysql.slow_log'
    )
    if (-not $Name) { $Name = "all-databases-$stamp" }
    $target = '(all databases)'
} else {
    $dumpArgs = @(
        "-u$User", "-p$Password",
        '--default-character-set=utf8mb4',
        '--single-transaction',   # consistent snapshot; does not lock the tables
        '--routines', '--triggers', '--events',
        '--set-gtid-purged=OFF',  # drop GTID statements so it restores elsewhere
        '--skip-comments',
        '--databases', $Database
    )
    if ($SchemaOnly) { $dumpArgs += '--no-data' }
    if (-not $Name) {
        $suffix = if ($SchemaOnly) { 'schema' } else { 'full' }
        $Name = "$Database-$suffix-$stamp"
    }
    $target = $Database
}

$outFile = Join-Path $dumpDir "$Name.sql"

# ---------------------------------------------------------------- dump
Write-Host ''
Write-Host 'Exporting database...' -ForegroundColor Cyan
Write-Host "  target : $target"
Write-Host "  mode   : $(if ($SchemaOnly) { 'schema only' } else { 'schema + data' })"
Write-Host "  output : $outFile"
Write-Host ''

# Redirect through cmd.exe rather than PowerShell's '>'.
# PowerShell 5.1 writes redirection output as UTF-16 with a BOM, which would
# corrupt mysqldump's UTF-8 content.
$quoted = ($dumpArgs | ForEach-Object {
    if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
}) -join ' '
$cmdLine = 'mysqldump ' + $quoted + ' > "' + $outFile + '"'

& cmd.exe /c $cmdLine
if ($LASTEXITCODE -ne 0) {
    throw "mysqldump failed (exit=$LASTEXITCODE). Check username/password and database name."
}

$item = Get-Item -LiteralPath $outFile
$sizeKb = [math]::Round($item.Length / 1KB, 1)

# ---------------------------------------------------------------- verify
# Self-check: a usable dump must contain DDL. An empty or truncated file is the
# most common silent failure here, so it is worth asserting rather than trusting.
$head = Get-Content -LiteralPath $outFile -TotalCount 60 -Encoding UTF8
$joined = $head -join "`n"
$hasCreate = $joined -match 'CREATE TABLE|CREATE DATABASE'
$lineCount = (Get-Content -LiteralPath $outFile -Encoding UTF8).Count

Write-Host ''
Write-Host "Done: $outFile" -ForegroundColor Green
Write-Host "  size        : $sizeKb KB"
Write-Host "  lines       : $lineCount"
Write-Host "  has DDL     : $hasCreate"
Write-Host ''
Write-Host 'To restore:' -ForegroundColor Cyan
Write-Host "  mysql -u root -p < `"$outFile`""
Write-Host ''

if (-not $hasCreate) {
    Write-Warning 'No CREATE statement found - the dump may be empty or truncated.'
    exit 1
}

if (-not $SchemaOnly -and -not $AllDatabases) {
    Write-Host 'NOTE: a full dump contains test accounts (BCrypt password hashes).' -ForegroundColor Yellow
    Write-Host '      Run with -SchemaOnly before sharing or submitting it.' -ForegroundColor Yellow
    Write-Host ''
}
