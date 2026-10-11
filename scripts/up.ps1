<#
.SYNOPSIS
    One-command startup for the whole stack (middleware + backend + frontend
    + observability), all running in Docker.

.DESCRIPTION
    Steps:
      1. Copy .env.example to .env when .env is missing (MYSQL_ROOT_PASSWORD
         inside is still empty - edit .env before exposing beyond localhost).
      2. docker compose up -d --build

    Which services start is controlled by COMPOSE_PROFILES in .env
    (default: web,observability -> everything). Run .\scripts\down.ps1-free
    teardown with: docker compose down (add -v to wipe data volumes).

    ASCII-only on purpose: Windows PowerShell 5.1 decodes non-BOM files with
    the ANSI code page; keeping the script ASCII avoids the entire mojibake
    class of failures (see scripts/run-app.ps1 for the full rationale).

.PARAMETER NoBuild
    Skip image build (use when only config changed and images are current).

.EXAMPLE
    .\scripts\up.ps1
    .\scripts\up.ps1 -NoBuild
#>
[CmdletBinding()]
param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot '.env'
$envExample = Join-Path $repoRoot '.env.example'

if (-not (Test-Path $envFile)) {
    if (-not (Test-Path $envExample)) { throw '.env.example not found in repo root' }
    Copy-Item $envExample $envFile
    Write-Warning '.env was missing - created it from .env.example.'
    Write-Warning 'MYSQL_ROOT_PASSWORD is still empty. Edit .env, or the stack will refuse to start.'
}

Push-Location $repoRoot
try {
    if ($NoBuild) {
        docker compose up -d
    }
    else {
        docker compose up -d --build
    }
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit=$LASTEXITCODE)" }

    Write-Host ''
    Write-Host '=== Stack started ===' -ForegroundColor Cyan
    Write-Host '  Frontend console : http://localhost:8080'
    Write-Host '  Backend API      : http://localhost:8081  (management :9091/actuator)'
    Write-Host '  Grafana          : http://localhost:3000 (admin/admin)'
    Write-Host '  Prometheus       : http://localhost:9090'
    Write-Host '  Alertmanager     : http://localhost:9093'
    Write-Host ''
    Write-Host '  Follow backend logs: docker compose logs -f backend'
}
finally {
    Pop-Location
}
