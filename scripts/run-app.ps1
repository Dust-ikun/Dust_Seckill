<#
.SYNOPSIS
    Start the seckill application with the `docker` profile, exporting config
    from .env into the process environment first.

.DESCRIPTION
    Why this script exists
    ----------------------
    `.env` is consumed by `docker compose`. The Java process, however, reads
    *shell environment variables*, not the file. When the two disagree you get
    "containers on 6379, application connecting to 6380" - which starts fine
    and then fails with a bare "Connection refused" that never hints at the
    real cause. This script pins the three steps (read .env -> export -> launch)
    so that .env stays the single source of truth.

    Why every message below is ASCII
    --------------------------------
    Windows PowerShell 5.1 reads script files using the *system ANSI code page*
    (GBK on a Chinese Windows) unless the file starts with a UTF-8 BOM.
    Non-ASCII text in this file therefore becomes mojibake and, worse, the
    mangled bytes can break string quoting and produce hard syntax errors.
    A BOM fixes it, but any tool that rewrites the file (editors, formatters,
    generators) may silently drop the BOM and reintroduce the failure.
    Keeping the script ASCII-only makes it immune to that entire class of
    problem. The Chinese explanations live in
    docs/AI监控Agent_部署与验收手册.md instead.

.PARAMETER SkipBuild
    Skip `mvn package` and launch the existing jar (use when only editing config).

.PARAMETER PrintOnly
    Print the resolved variables and port checks, then exit without launching.
    Useful for answering "what did it actually read?".

.EXAMPLE
    .\scripts\run-app.ps1
    .\scripts\run-app.ps1 -SkipBuild
    .\scripts\run-app.ps1 -PrintOnly
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$PrintOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot '.env'

# Only export the variables the application actually reads.
# .env also holds compose-only keys (GRAFANA_ADMIN_PASSWORD and friends);
# pushing those into the JVM environment would make "what did the app read"
# impossible to answer, which is exactly what you need during troubleshooting.
$appVars = @(
    'MYSQL_HOST_PORT',
    'MYSQL_ROOT_PASSWORD',
    'REDIS_HOST_PORT',
    'DEEPSEEK_API_KEY'
)

$loaded = @{}
if (-not (Test-Path $envFile)) {
    Write-Warning ".env not found; falling back to application.yaml defaults."
    Write-Warning "To customise: Copy-Item .env.example .env"
} else {
    # Read as UTF-8 explicitly.
    # Get-Content in Windows PowerShell 5.1 defaults to the ANSI code page, which
    # mis-decodes the UTF-8 Chinese comments in .env. The mis-decoding cascades:
    # a comment line swallows the config entry that follows it, e.g.
    #     "# ...note...REDIS_HOST_PORT=6379"   <- whole line becomes a comment
    # so REDIS_HOST_PORT is never seen even though the file is perfectly correct.
    # ReadAllLines + UTF8 behaves identically on PowerShell 5.1 and 7 and does not
    # depend on $OutputEncoding or chcp.
    $envLines = [System.IO.File]::ReadAllLines($envFile, [System.Text.UTF8Encoding]::new($false))
    foreach ($line in $envLines) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        $idx = $t.IndexOf('=')
        if ($idx -lt 1) { continue }
        $k = $t.Substring(0, $idx).Trim()
        $v = $t.Substring($idx + 1).Trim()
        if ($appVars -contains $k) { $loaded[$k] = $v }
    }
}

Write-Host '=== Resolved application variables (from .env) ===' -ForegroundColor Cyan
foreach ($k in $appVars) {
    $v = $loaded[$k]
    if ([string]::IsNullOrEmpty($v)) {
        # Say "empty" out loud. An empty LLM key is a *legal* state (the agent
        # degrades gracefully), but if it is not printed people assume the script
        # failed to read it and start editing code.
        $display = if ($k -eq 'DEEPSEEK_API_KEY') {
            '(empty -> LLM disabled, alerts are still stored but not diagnosed)'
        } else {
            '(unset -> using application.yaml default)'
        }
    } elseif ($k -eq 'DEEPSEEK_API_KEY') {
        $display = "set ($($v.Length) chars, starts with $($v.Substring(0, [Math]::Min(3, $v.Length)))...)"
    } else {
        $display = $v
    }
    Write-Host ("  {0,-22} = {1}" -f $k, $display)
}

foreach ($k in $loaded.Keys) {
    if (-not [string]::IsNullOrEmpty($loaded[$k])) {
        Set-Item -Path "env:$k" -Value $loaded[$k]
    }
}

# Pre-flight port check.
# "Container is down" and "another process owns the port" look identical from the
# application side (Connection refused). Telling them apart here removes half of
# the "the app will not start" problem space before it can waste any time.
$mysqlPort = if ($loaded['MYSQL_HOST_PORT']) { $loaded['MYSQL_HOST_PORT'] } else { '3306' }
$redisPort = if ($loaded['REDIS_HOST_PORT']) { $loaded['REDIS_HOST_PORT'] } else { '6379' }

Write-Host ''
Write-Host '=== Middleware port check ===' -ForegroundColor Cyan
$allUp = $true
foreach ($pair in @(@('MySQL', $mysqlPort), @('Redis', $redisPort))) {
    $name, $port = $pair
    $conn = Test-NetConnection -ComputerName '127.0.0.1' -Port ([int]$port) `
        -InformationLevel Quiet -WarningAction SilentlyContinue
    if ($conn) {
        Write-Host ("  [OK]   {0,-5} 127.0.0.1:{1} reachable" -f $name, $port) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] {0,-5} 127.0.0.1:{1} unreachable" -f $name, $port) -ForegroundColor Red
        $allUp = $false
    }
}
if (-not $allUp) {
    Write-Warning 'Middleware not ready. Run: docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker'
    if (-not $PrintOnly) {
        $ans = Read-Host 'Start the application anyway? (y/N)'
        if ($ans -ne 'y') { Write-Host 'Aborted.'; exit 1 }
    }
}

if ($PrintOnly) {
    Write-Host ''
    Write-Host '(-PrintOnly given; not launching)' -ForegroundColor Yellow
    exit 0
}

if (-not $SkipBuild) {
    Write-Host ''
    Write-Host '=== Build (mvn -o package -DskipTests) ===' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        & mvn -o -B clean package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Build failed (exit=$LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

$jar = Get-ChildItem (Join-Path $repoRoot 'target') -Filter 'seckill-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $jar) {
    throw 'target/seckill-*.jar not found. Run: mvn -o package -DskipTests'
}

Write-Host ''
Write-Host "=== Launching $($jar.Name) (profile=docker) ===" -ForegroundColor Cyan
Write-Host '  business port 8081 / management port 9091; Ctrl+C to stop' -ForegroundColor DarkGray
Write-Host ''

# Pass the profile on the command line rather than via an environment variable:
# command-line arguments win, and it makes "this was started with the docker
# profile" visible to anyone reading the script instead of hidden in the shell.
& java -jar $jar.FullName --spring.profiles.active=docker
