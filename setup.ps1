# =============================================================================
#  First-run setup for a new machine.
#
#  NOTE: this file must stay PURE ASCII. Windows PowerShell 5.1 reads .ps1 with
#  the system ANSI code page, so Chinese literals get mangled and break string
#  terminators, tearing the whole script apart at parse time.
#  Chinese documentation: docs/环境搭建与部署.md
#
#  What it does, in order:
#    1. checks that java / mvn / mysql are on PATH (node is optional)
#    2. verifies the Java version is 17+
#    3. connects to MySQL with the given credentials
#    4. creates the database if it does not exist
#    5. builds the backend jar
#    6. prints exactly how to run it
#
#  Usage:
#    .\setup.ps1
#    .\setup.ps1 -DbPassword "yourpassword"
#    .\setup.ps1 -SkipBuild
#
#  Why ErrorActionPreference is 'Continue' rather than 'Stop':
#    mysql and java write harmless notices to stderr ("Using a password on the
#    command line is insecure", the JVM version banner). With 'Stop' PowerShell
#    turns any stderr output into a fatal NativeCommandError, so the script dies
#    on a warning. Native commands are checked via $LASTEXITCODE instead, which
#    is the correct way to detect their failure.
# =============================================================================
[CmdletBinding()]
param(
    [string]$DbHost = '127.0.0.1',
    [int]   $DbPort = 3306,
    [string]$DbName = 'personal_ledger',
    [string]$DbUser = 'root',
    [string]$DbPassword = '123456',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$backend = Join-Path $root 'backend'
$script:failures = 0

function Step($n, $text) {
    Write-Host ''
    Write-Host "[$n] $text" -ForegroundColor Cyan
}
function Ok($text)   { Write-Host "    OK   $text" -ForegroundColor Green }
function Warn($text) { Write-Host "    WARN $text" -ForegroundColor Yellow }
function Bad($text)  { Write-Host "    FAIL $text" -ForegroundColor Red; $script:failures++ }
function Note($text) { Write-Host "         $text" -ForegroundColor DarkGray }

# ----------------------------------------------------------------- 1. toolchain
Step 1 'Checking the toolchain'

$missing = @()
foreach ($tool in @(
    @{ exe = 'java';  args = '-version';  label = 'Java';         required = $true  },
    @{ exe = 'mvn';   args = '-v';        label = 'Maven';         required = $true  },
    @{ exe = 'mysql'; args = '--version'; label = 'MySQL client';  required = $true  },
    @{ exe = 'node';  args = '--version'; label = 'Node.js';       required = $false }
)) {
    $cmd = Get-Command $tool.exe -ErrorAction SilentlyContinue
    if (-not $cmd) {
        if ($tool.required) { Bad "$($tool.label) not found on PATH ($($tool.exe))"; $missing += $tool.exe }
        else                { Warn "$($tool.label) not found (optional)" }
        continue
    }
    # 2>&1 inside cmd: merges stderr so a version banner is not treated as failure
    $v = (& cmd.exe /c "$($tool.exe) $($tool.args) 2>&1" | Select-Object -First 1)
    $v = ("$v").Trim()
    if ($v) { Ok "$($tool.label) -> $v" } else { Ok "$($tool.label) -> $($cmd.Source)" }
}
Note 'Node is only needed to change the frontend; the built frontend is'
Note 'already committed under backend/src/main/resources/static/.'

if ($missing.Count -gt 0) {
    Write-Host ''
    Write-Host "Missing required tools: $($missing -join ', ')" -ForegroundColor Red
    Write-Host 'Install them, then re-run this script.' -ForegroundColor Red
    Write-Host 'Versions and links: docs/环境搭建与部署.md' -ForegroundColor Red
    exit 1
}

# ----------------------------------------------------------------- 2. java version
Step 2 'Checking Java version (17 or newer required)'
$javaRaw = (& cmd.exe /c 'java -version 2>&1' | Select-Object -First 1)
if ("$javaRaw" -match '"(\d+)') {
    $major = [int]$Matches[1]
    if ($major -ge 17) { Ok "Java $major" }
    else { Bad "Java $major found, but Spring Boot 3 needs 17+" }
} else {
    Warn "Could not parse the Java version from: $javaRaw"
}

# ----------------------------------------------------------------- 3. MySQL
Step 3 "Connecting to MySQL at ${DbHost}:${DbPort} as $DbUser"

$mysqlArgs = @("-h$DbHost", "-P$DbPort", "-u$DbUser", "-p$DbPassword", '--connect-timeout=5')
$probe = (& cmd.exe /c "mysql -h$DbHost -P$DbPort -u$DbUser -p$DbPassword --connect-timeout=5 -N -B -e ""SELECT VERSION();"" 2>&1")
$probeExit = $LASTEXITCODE
$probeText = ("$probe").Trim()

# mysql emits "[Warning] Using a password on the command line interface can be
# insecure." on stderr. Merged into stdout it ends up on the SAME line as the
# version number, so the warning has to be stripped before matching - otherwise
# the check fails even though the connection succeeded.
$probeText = ($probeText -replace 'mysql:.*?insecure\.\s*', '').Trim()

if ($probeExit -ne 0 -or $probeText -notmatch '^\d+\.\d+') {
    Bad 'Cannot connect to MySQL.'
    Write-Host ''
    Write-Host '    Raw output:' -ForegroundColor DarkGray
    "$probe" -split "`n" | Select-Object -First 3 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    Write-Host ''
    Write-Host '    Checklist:' -ForegroundColor Yellow
    Write-Host '      - is the MySQL service running?' -ForegroundColor Yellow
    Write-Host '      - is the password correct? pass it explicitly:' -ForegroundColor Yellow
    Write-Host '          .\setup.ps1 -DbPassword "your-password"' -ForegroundColor Yellow
    exit 1
}
Ok "MySQL $probeText"

# ----------------------------------------------------------------- 4. database
Step 4 "Ensuring database '$DbName' exists"

# CREATE DATABASE IF NOT EXISTS is idempotent, so this is safe to run against an
# existing database. Detect existence with SHOW DATABASES LIKE (exact match)
# rather than by parsing information_schema output, which is easy to mis-match.
$dbCmd = "mysql -h$DbHost -P$DbPort -u$DbUser -p$DbPassword -N -B"
$before = (& cmd.exe /c "$dbCmd -e ""SHOW DATABASES LIKE '$DbName';"" 2>&1")
$before = ("$before" -replace 'mysql:.*?insecure\.\s*', '').Trim()
$existed = ($before -eq $DbName)

& cmd.exe /c "$dbCmd -e ""CREATE DATABASE IF NOT EXISTS ``$DbName`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"" 2>&1" | Out-Null
if ($LASTEXITCODE -ne 0) {
    Bad "Failed to create database '$DbName'"
} elseif ($existed) {
    Ok "database '$DbName' already existed - left untouched"
    Note 'This script never drops or overwrites existing data.'
} else {
    Ok "database '$DbName' created (empty)"
    Note 'Flyway will build all 11 tables on first startup.'
}

# ----------------------------------------------------------------- 5. maven repo
Step 5 'Checking the Maven local repository'
if (Test-Path (Join-Path $root '.m2-portable')) {
    Ok 'found bundled repository .m2-portable - offline build is possible'
} else {
    Ok 'using the standard repository %USERPROFILE%\.m2\repository'
    Note 'First build downloads dependencies (~200-400 MB).'
    Note 'If that is slow, enable the Aliyun mirror:'
    Note '  cd backend; mvn -s settings.xml.optional clean package'
}

# ----------------------------------------------------------------- 6. build
if (-not $SkipBuild) {
    Step 6 'Building the backend jar'
    Push-Location $backend
    $buildOut = (& cmd.exe /c 'mvn -B -q clean package -DskipTests 2>&1')
    $buildExit = $LASTEXITCODE
    Pop-Location

    if ($buildExit -ne 0) {
        Bad 'Maven build failed. Output:'
        "$buildOut" -split "`n" | Select-Object -Last 25 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    } else {
        $jar = Get-ChildItem (Join-Path $backend 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notmatch 'original' } | Select-Object -First 1
        if ($jar) { Ok "built $($jar.Name)  ($([math]::Round($jar.Length/1MB,1)) MB)" }
        else { Bad 'Build reported success but no jar was produced' }
    }
} else {
    Step 6 'Skipping build (-SkipBuild)'
}

# ----------------------------------------------------------------- summary
Write-Host ''
if ($script:failures -gt 0) {
    Write-Host "Setup finished with $($script:failures) problem(s). See messages above." -ForegroundColor Red
    exit 1
}

Write-Host ('=' * 66) -ForegroundColor Green
Write-Host ' Setup complete.' -ForegroundColor Green
Write-Host ('=' * 66) -ForegroundColor Green
Write-Host ''
Write-Host ' To run, pick either way:' -ForegroundColor Cyan
Write-Host ''
Write-Host '   A. IntelliJ IDEA (recommended)'
Write-Host '      Open the backend folder, then run'
Write-Host '      com.example.ledger.LedgerApplication'
Write-Host ''
Write-Host '   B. Command line'
Write-Host "      cd `"$backend`""
Write-Host '      java -jar target\personal-ledger-1.0.0-SNAPSHOT.jar'
Write-Host ''
Write-Host ' Then open  http://127.0.0.1:8080' -ForegroundColor Cyan
Write-Host ' The frontend is bundled in the jar, so nothing else needs to start.'
Write-Host ''
Write-Host ' If your MySQL credentials differ from the defaults, set them first:' -ForegroundColor Yellow
Write-Host "      `$env:DB_USERNAME = 'your-user'" -ForegroundColor Yellow
Write-Host "      `$env:DB_PASSWORD = 'your-password'" -ForegroundColor Yellow
Write-Host ''
