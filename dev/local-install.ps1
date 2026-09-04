# DEV-ONLY: installs the patch using the LOCALLY BUILT jar, for fast
# iteration while working on the agent. End users should use dawn-patcher.ps1
# (repo root) instead -- this script assumes a local checkout + build, not
# the curl-installable release flow.
#
# Safe to re-run: skips work that's already done.

$ErrorActionPreference = "Stop"

$repoRoot     = Join-Path $PSScriptRoot ".."
$cfgPath      = "$env:LOCALAPPDATA\Dawn\Dawn (Feather)\app\Dawn (Feather).cfg"
$backupPath   = Join-Path $repoRoot "Dawn (Feather).cfg.orig-backup"
$agentJar     = Join-Path $repoRoot "agent\dawn-patcher-agent.jar"
$settingsPath = "$env:APPDATA\.dawn\launcher-settings.json"
$targetWidth  = 1920  # must match DawnAdRailAgent.MAINKT_NEW_WIDTH

if (-not (Test-Path $agentJar)) {
    Write-Host "Agent jar not found at: $agentJar"
    Write-Host "Build it first (see agent/README or just re-run the javac/jar build steps)."
    exit 1
}
if (-not (Test-Path $cfgPath)) {
    Write-Host "Dawn config not found at: $cfgPath -- is Dawn installed at the expected path?"
    exit 1
}

$cfgContent = Get-Content $cfgPath -Raw
if ($cfgContent -match [regex]::Escape($agentJar)) {
    Write-Host "Already installed (javaagent line already present in the cfg). Nothing to do."
} else {
    if (-not (Test-Path $backupPath)) {
        Copy-Item $cfgPath $backupPath
        Write-Host "Backed up original config to: $backupPath"
    } else {
        Write-Host "Backup already exists at: $backupPath (not overwriting)"
    }

    $newContent = $cfgContent -replace '(\[JavaOptions\]\r?\n)', "`$1java-options=-javaagent:$agentJar`r`n"
    if ($newContent -eq $cfgContent) {
        Write-Host "ERROR: could not find [JavaOptions] section in the cfg -- Dawn's config format may have changed."
        exit 1
    }
    Set-Content -Path $cfgPath -Value $newContent -NoNewline
    Write-Host "Installed: added -javaagent line to $cfgPath"
}

# Cosmetic only (T.a() is patched before Dawn ever reads this file, so it's
# not functionally required) -- keeps the settings file's own recorded width
# consistent with what the window will actually be.
if (Test-Path $settingsPath) {
    $settingsContent = Get-Content $settingsPath -Raw
    $newSettings = $settingsContent -replace '"launcherWindowWidthPoints":\d+', "`"launcherWindowWidthPoints`":$targetWidth"
    if ($newSettings -ne $settingsContent) {
        Set-Content -Path $settingsPath -Value $newSettings -NoNewline
        Write-Host "Also updated persisted window width in: $settingsPath"
    }
}

$running = Get-Process "Dawn (Feather)" -ErrorAction SilentlyContinue
if ($running) {
    Write-Host ""
    Write-Host "Dawn is currently running -- restart it for the patch to take effect."
} else {
    Write-Host ""
    Write-Host "Done. Launch Dawn normally (double-click, Start Menu, etc.) and the patch will be active."
}
Write-Host "Run local-uninstall.ps1 to revert to stock Dawn at any time."
