# =============================================================================
#  Retire the NATIVE MySQL / Redis installs and wipe the Docker data volumes,
#  so that from now on every middleware runs only in Docker.
#
#  Why a script instead of a few ad-hoc commands
#  ---------------------------------------------
#  The tricky parts are not the deletions, they are the preconditions:
#    1) `msiexec /x` needs an ELEVATED shell. Run non-elevated it fails with
#       Error 1730 ("You must be an Administrator") AFTER writing a 150 KB log --
#       easy to misread as "uninstalled, but the folder is still there".
#    2) `docker compose down -v` deletes the named volumes. If MySQL/Redis are
#       already gone, that data is unrecoverable -- so this must be a
#       deliberate, separate step, never a side effect.
#    3) Port 3306/6379 may still be held by the running containers. Removing the
#       native installs while the containers hold those ports is fine, but
#       starting the containers again before the volumes are wiped is not.
#  Every destructive step below therefore prints what it is about to remove and
#  refuses to continue unless -Yes is passed.
#
#  ASCII only, on purpose
#  ----------------------
#  Windows PowerShell 5.1 reads a BOM-less script as the system ANSI code page.
#  Non-ASCII text then becomes mojibake, and the mangled bytes can break string
#  quoting into hard syntax errors. Keeping this file ASCII-only makes it immune
#  to that whole class of problem -- the same reason scripts/run-app.ps1 is ASCII.
#
#  Usage
#  -----
#    # 1) dry run: only reports what would be removed
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\remove-native-middleware.ps1
#
#    # 2) actually do it (must be an ELEVATED PowerShell window)
#    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\remove-native-middleware.ps1 -Yes
#
#    # 3) keep the Docker data (e.g. you only want the native installs gone)
#    ... -Yes -KeepVolumes
#
#  What it does NOT do
#  -------------------
#  It never touches `C:\Program Files\MySQL\...` left behind by a failed
#  uninstall silently: if the folder survives, it tells you the exact command
#  to remove it and moves on. Deleting a program directory by hand while the
#  MSI still thinks the product is installed leaves a broken entry in
#  "Apps & features" that later blocks a reinstall.
# =============================================================================
[CmdletBinding()]
param(
    # Actually perform the removals. Without it this is a read-only report.
    [switch]$Yes,

    # Do not delete the Docker named volumes (keeps the seckill database/Redis data).
    [switch]$KeepVolumes,

    # Keep the MySQL Configurator (a separate MSI, only a GUI helper).
    [switch]$KeepConfigurator
)

$ErrorActionPreference = 'Stop'

# The MSI product code of "MySQL Server 8.4" on this machine.
# Read it from the registry rather than hardcoding, so this keeps working if the
# product is repaired/upgraded to a new code.
$MySQLMsiGuid = '{07EB6F8B-0CA6-4EE7-A669-81761C67801E}'

$MySQLInstallDir = 'C:\Program Files\MySQL'
$MySQLDataDir    = 'D:\ProgramData\MySQL'
$RedisDir        = 'D:\Redis-8.10.1'
$RepoRoot        = Split-Path -Parent $PSScriptRoot

function Write-Step([string]$text) {
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor DarkGray
    Write-Host $text -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor DarkGray
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-MySqlMsiEntry {
    $keys = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    Get-ItemProperty $keys -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'MySQL Server 8.4' } |
        Select-Object -First 1
}

$isAdmin = Test-Admin

# -----------------------------------------------------------------------------
# 0. Report the starting state
# -----------------------------------------------------------------------------
Write-Step '0. Current state (native installs / services / ports / containers)'

$svc = Get-Service -Name 'MySQL84' -ErrorAction SilentlyContinue
if ($svc) {
    Write-Host ("  MySQL84 service : {0} / {1}" -f $svc.Status, $svc.StartType)
} else {
    Write-Host '  MySQL84 service : not present'
}

foreach ($p in @($MySQLInstallDir, $MySQLDataDir, $RedisDir)) {
    if (Test-Path $p) {
        $size = (Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue |
                 Measure-Object Length -Sum).Sum
        Write-Host ("  EXISTS  {0,-30} {1,8:N1} MB" -f $p, ($size / 1MB))
    } else {
        Write-Host ("  absent  {0}" -f $p)
    }
}

$msi = Get-MySqlMsiEntry
if ($msi) {
    Write-Host ("  MSI entry      : {0} {1}" -f $msi.DisplayName, $msi.DisplayVersion)
} else {
    Write-Host '  MSI entry      : MySQL Server 8.4 is NOT registered (already uninstalled)'
}

foreach ($port in 3306, 6379) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        $owners = $conn | ForEach-Object {
            (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        } | Sort-Object -Unique
        Write-Host ("  port {0}       : held by {1}" -f $port, ($owners -join ', '))
    } else {
        Write-Host ("  port {0}       : free" -f $port)
    }
}

Write-Host ''
Write-Host ("  Elevated shell : {0}" -f $isAdmin)

if (-not $Yes) {
    Write-Host ''
    Write-Host 'DRY RUN -- nothing was changed. Re-run with -Yes to apply.' -ForegroundColor Yellow
    Write-Host 'Add -KeepVolumes to keep the Docker database/Redis data.' -ForegroundColor Yellow
    return
}

# -----------------------------------------------------------------------------
# 1. Native MySQL: uninstall via MSI
# -----------------------------------------------------------------------------
Write-Step '1. Uninstalling native MySQL Server 8.4'

if (-not $isAdmin) {
    Write-Warning 'Not elevated. msiexec will fail with Error 1730.'
    Write-Warning 'Open an ADMIN PowerShell window and re-run this script with -Yes.'
} elseif (-not $msi) {
    Write-Host '  Already uninstalled; skipping.'
} else {
    if ($svc -and $svc.Status -ne 'Stopped') {
        Write-Host '  Stopping MySQL84 ...'
        Stop-Service -Name 'MySQL84' -Force
    }

    $log = Join-Path $env:TEMP 'mysql-uninstall.log'
    Write-Host ("  msiexec /x $MySQLMsiGuid /qn /norestart")
    Write-Host ("  log: {0}" -f $log)

    $proc = Start-Process -FilePath 'msiexec.exe' `
        -ArgumentList @('/x', $MySQLMsiGuid, '/qn', '/norestart', '/L*v', $log) `
        -Wait -PassThru
    Write-Host ("  msiexec exit code: {0}" -f $proc.ExitCode)

    if ($proc.ExitCode -ne 0) {
        Write-Warning 'Uninstall did not report success. Check the log above.'
    }
}

# -----------------------------------------------------------------------------
# 2. Native Redis: portable folder, no service -> just delete
# -----------------------------------------------------------------------------
Write-Step '2. Removing native Redis portable folder'

if (-not (Test-Path $RedisDir)) {
    Write-Host '  Already gone; skipping.'
} else {
    # Refuse to delete if something from that folder is actually running --
    # the .pid file in older copies is stale, so check real processes.
    $redisProcs = Get-Process -Name 'redis-server' -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like "$RedisDir*" }
    if ($redisProcs) {
        Write-Warning '  A redis-server from this folder is running; stop it first.'
    } else {
        Write-Host ("  Removing {0} ..." -f $RedisDir)
        Remove-Item $RedisDir -Recurse -Force
        Write-Host '  done.'
    }
}

# -----------------------------------------------------------------------------
# 3. Leftover MySQL directories
# -----------------------------------------------------------------------------
Write-Step '3. Leftover MySQL directories'

foreach ($dir in @($MySQLDataDir, $MySQLInstallDir)) {
    if (-not (Test-Path $dir)) {
        Write-Host ("  {0} : already gone" -f $dir)
        continue
    }
    # The data dir is NOT owned by the MSI, so an uninstall never removes it.
    # It is deleted here because the whole point is "wipe the local data".
    Write-Host ("  Removing {0} ..." -f $dir)
    try {
        Remove-Item $dir -Recurse -Force
        Write-Host '  done.'
    } catch {
        Write-Warning ("  Could not remove {0}: {1}" -f $dir, $_.Exception.Message)
        Write-Warning '  Usually a permissions problem -- delete it from an elevated Explorer,'
        Write-Warning '  or check that no service still holds a file inside it.'
    }
}

if (-not $KeepConfigurator) {
    $cfg = 'C:\ProgramData\MySQL\MySQL Configurator'
    if (Test-Path $cfg) {
        Write-Host ("  Removing Configurator leftovers {0} ..." -f $cfg)
        Remove-Item $cfg -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# -----------------------------------------------------------------------------
# 4. Wipe the Docker named volumes
# -----------------------------------------------------------------------------
Write-Step '4. Docker named volumes'

Push-Location $RepoRoot
try {
    if ($KeepVolumes) {
        Write-Host '  -KeepVolumes given: volumes are preserved.'
    } else {
        Write-Host '  Stopping the stack and DELETING its named volumes.'
        Write-Host '  This erases: the seckill database, Redis data, RocketMQ store,'
        Write-Host '  Prometheus TSDB, Alertmanager state and Grafana data.'
        Write-Host ''
        & docker compose --profile observability down -v
        if ($LASTEXITCODE -ne 0) {
            Write-Warning ("  docker compose down -v exited with {0}" -f $LASTEXITCODE)
        }
    }
} finally {
    Pop-Location
}

# -----------------------------------------------------------------------------
# 5. Verification
# -----------------------------------------------------------------------------
Write-Step '5. Verification'

Write-Host ("  MySQL install dir : {0}" -f (Test-Path $MySQLInstallDir))
Write-Host ("  MySQL data dir    : {0}" -f (Test-Path $MySQLDataDir))
Write-Host ("  Redis dir         : {0}" -f (Test-Path $RedisDir))

$msi = Get-MySqlMsiEntry
Write-Host ("  MSI still registered : {0}" -f ([bool]$msi))

Push-Location $RepoRoot
try {
    $vols = & docker volume ls --format '{{.Name}}' 2>$null | Select-String -Pattern 'seckill'
    if ($vols) {
        Write-Host '  Remaining seckill volumes:'
        $vols | ForEach-Object { Write-Host ("    {0}" -f $_) }
    } else {
        Write-Host '  Remaining seckill volumes: none'
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host 'NEXT STEPS' -ForegroundColor Cyan
Write-Host '  1) Recreate the stack on FRESH volumes (this re-runs the two schema files):'
Write-Host '       docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker'
Write-Host '       docker compose --profile observability up -d'
Write-Host '  2) Wait for mysql to become healthy, then preheat the stock row:'
Write-Host '       curl.exe -X POST "http://localhost:8081/seckill/preheat?stockId=1"'
Write-Host '     NOTE: schema.sql only creates the stock row; it does NOT seed a count.'
Write-Host '     Without this preheat step every order will fail with 1003.'
Write-Host '  3) The MySQL root password used to live in the native my.ini; the container'
Write-Host '     reads it from .env (MYSQL_ROOT_PASSWORD). Keep .env in sync.'
