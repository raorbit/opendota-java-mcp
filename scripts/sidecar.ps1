<#
.SYNOPSIS
  Lifecycle wrapper for the shared OpenDota sidecar (PowerShell).

.DESCRIPTION
  Usage:
    scripts\sidecar.ps1 start     # build (if needed) and start the sidecar in the background
    scripts\sidecar.ps1 stop      # stop the running sidecar
    scripts\sidecar.ps1 status    # query GET /health
    scripts\sidecar.ps1 restart   # stop then start

  One sidecar per machine: start refuses to launch a second when one is already running.
  Set $env:OPENDOTA_API_KEY before `start` so the sidecar holds the key. Override the port
  with $env:OPENDOTA_SIDECAR_PORT (default 31337).
#>
param(
  [Parameter(Position = 0)]
  [ValidateSet('start', 'stop', 'restart', 'status')]
  [string]$Command = 'status'
)

$ErrorActionPreference = 'Stop'
$RootDir = Split-Path -Parent $PSScriptRoot
$Jar     = Join-Path $RootDir 'sidecar\target\opendota-sidecar-1.2.0.jar'
$RunDir  = Join-Path $RootDir 'sidecar\.run'
$PidFile = Join-Path $RunDir 'sidecar.pid'
$LogFile = Join-Path $RunDir 'sidecar.log'
$Port    = if ($env:OPENDOTA_SIDECAR_PORT) { $env:OPENDOTA_SIDECAR_PORT } else { '31337' }

function Get-SidecarProcess {
  if (-not (Test-Path $PidFile)) { return $null }
  $procId = Get-Content $PidFile | Select-Object -First 1
  return Get-Process -Id $procId -ErrorAction SilentlyContinue
}

function Start-Sidecar {
  if (Get-SidecarProcess) {
    Write-Host "sidecar already running (pid $(Get-Content $PidFile))"; return
  }
  if (-not (Test-Path $Jar)) {
    Write-Host 'building sidecar jar...'
    & mvn -B -f (Join-Path $RootDir 'sidecar\pom.xml') -q clean package
  }
  New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
  Write-Host "starting sidecar on 127.0.0.1:$Port (logs: $LogFile)"
  $proc = Start-Process -FilePath 'java' -ArgumentList '-jar', $Jar `
    -RedirectStandardOutput $LogFile -RedirectStandardError "$LogFile.err" `
    -NoNewWindow -PassThru
  $proc.Id | Out-File -Encoding ascii $PidFile
  # Verify it actually came up rather than reporting a stale 'started' for a JVM that died at once
  # (e.g. BindException because the port is taken). Poll /health for ~5s; bail if the process exits.
  for ($i = 0; $i -lt 25; $i++) {
    if ($proc.HasExited) {
      Write-Host "sidecar exited during startup (exit $($proc.ExitCode)) - see $LogFile"
      Remove-Item $PidFile -ErrorAction SilentlyContinue
      return
    }
    try {
      Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 1 | Out-Null
      Write-Host "started (pid $($proc.Id)); /health ok on 127.0.0.1:$Port"
      return
    } catch {
      Start-Sleep -Milliseconds 200
    }
  }
  Write-Host "started (pid $($proc.Id)) but /health did not answer within 5s - check $LogFile"
}

function Stop-Sidecar {
  $proc = Get-SidecarProcess
  if (-not $proc) { Write-Host 'sidecar not running'; Remove-Item $PidFile -ErrorAction SilentlyContinue; return }
  Write-Host "stopping sidecar (pid $($proc.Id))"
  Stop-Process -Id $proc.Id -Force
  # Wait for the process to actually exit (and release the port) before returning, so a following
  # `restart` start doesn't lose the bind race and silently die on BindException.
  try { Wait-Process -Id $proc.Id -Timeout 5 -ErrorAction Stop } catch {}
  Remove-Item $PidFile -ErrorAction SilentlyContinue
}

function Get-SidecarStatus {
  if (Get-SidecarProcess) { Write-Host "process: running (pid $(Get-Content $PidFile))" }
  else { Write-Host 'process: not running' }
  try {
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 3
    Write-Host "health: $($r | ConvertTo-Json -Compress)"
  } catch {
    Write-Host 'health: (unreachable)'
  }
}

switch ($Command) {
  'start'   { Start-Sidecar }
  'stop'    { Stop-Sidecar }
  'restart' { Stop-Sidecar; Start-Sidecar }
  'status'  { Get-SidecarStatus }
}
