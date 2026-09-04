# DEV-ONLY: reverts Dawn to stock by restoring the backed-up original config.
# End users should use dawn-patcher.ps1 (repo root) instead. Safe to re-run.

$ErrorActionPreference = "Stop"

$repoRoot   = Join-Path $PSScriptRoot ".."
$cfgPath    = "$env:LOCALAPPDATA\Dawn\Dawn (Feather)\app\Dawn (Feather).cfg"
$backupPath = Join-Path $repoRoot "Dawn (Feather).cfg.orig-backup"

if (-not (Test-Path $backupPath)) {
    Write-Host "No backup found at: $backupPath"
    Write-Host "Either the patch was never installed, or the backup was moved/deleted -- nothing to safely revert to."
    exit 1
}

$running = Get-Process "Dawn (Feather)" -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "Stopping running Dawn process(es) first..."
    $running | Stop-Process -Force -Confirm:$false
    Start-Sleep -Milliseconds 500
}

Copy-Item $backupPath $cfgPath -Force
Write-Host "Restored stock config from: $backupPath"
Write-Host "Dawn is back to fully stock. Launch it normally whenever you like."
Write-Host "(The backup file is left in place in case you want to re-install later.)"
