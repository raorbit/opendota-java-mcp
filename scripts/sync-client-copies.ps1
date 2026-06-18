<#
.SYNOPSIS
  Re-copy the canonical OpenDota client classes from the root module into the sidecar module.

.DESCRIPTION
  The sidecar is a deliberately standalone build that cannot depend on the root jar, so it keeps
  byte-identical COPIES of four client classes. The root copies under
  src\main\java\com\raorbit\opendota\client\ are the SINGLE SOURCE OF TRUTH — edit them there, then
  run this to mirror the change into the sidecar, and commit both. ClientCopyDriftTest fails the
  sidecar build (hard, under CI) if the copies ever diverge, so this is the sanctioned way to sync.
#>
$ErrorActionPreference = 'Stop'
$RootDir = Split-Path -Parent $PSScriptRoot
$Pkg = 'src\main\java\com\raorbit\opendota\client'
$Files = 'OpenDotaClient', 'OpenDotaException', 'RateLimiter', 'TtlCache'

foreach ($f in $Files) {
  Copy-Item -Path (Join-Path $RootDir "$Pkg\$f.java") `
            -Destination (Join-Path $RootDir "sidecar\$Pkg\$f.java") -Force
  Write-Host "synced $f.java -> sidecar/"
}
Write-Host "done - review 'git diff sidecar/' and commit the synced copies."
