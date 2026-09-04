# dawn-patcher.ps1 -- toggle installer for the Dawn (dawn.gg) launcher patch.
#
# Run it with:
#   irm https://github.com/oneauraaa/dawn-patcher/releases/latest/download/dawn-patcher.ps1 | iex
#
# Re-running this script TOGGLES the patch: installs it if it's not present,
# reverts to stock Dawn if it is. Safe to run repeatedly.
#
# What it does: downloads dawn-patcher-agent.jar (a Java agent, source at
# https://github.com/oneauraaa/dawn-patcher/tree/main/agent) into a local
# cache folder, then wires it into Dawn's own jpackage launch config
# (app\Dawn (Feather).cfg) as a static -javaagent. This makes it apply on
# every normal launch (double-click, Start Menu, taskbar), not just a
# special shortcut -- required because one of the patches (window sizing)
# has to land before Dawn's own startup code runs.
#
# A Dawn auto-update may regenerate the .cfg file and silently revert to
# stock; just run this script again if that happens.

$ErrorActionPreference = "Stop"

$installDir   = "$env:LOCALAPPDATA\DawnPatcher"
$agentJar     = Join-Path $installDir "dawn-patcher-agent.jar"
$backupPath   = Join-Path $installDir "Dawn (Feather).cfg.orig-backup"
$cfgPath      = "$env:LOCALAPPDATA\Dawn\Dawn (Feather)\app\Dawn (Feather).cfg"
$settingsPath = "$env:APPDATA\.dawn\launcher-settings.json"
$targetWidth  = 1920 # must match DawnAdRailAgent.MAINKT_NEW_WIDTH
$releaseUrl   = "https://github.com/oneauraaa/dawn-patcher/releases/latest/download/dawn-patcher-agent.jar"

function Stop-Dawn {
    $running = Get-Process "Dawn (Feather)" -ErrorAction SilentlyContinue
    if ($running) {
        Write-Host "Stopping running Dawn process(es)..."
        $running | Stop-Process -Force -Confirm:$false
        Start-Sleep -Milliseconds 500
    }
}

if (-not (Test-Path $cfgPath)) {
    Write-Host "Dawn config not found at: $cfgPath"
    Write-Host "Is Dawn installed? This script only supports the standard per-user install location."
    exit 1
}

$cfgContent  = Get-Content $cfgPath -Raw
$javaAgentLine = "java-options=-javaagent:$agentJar"
$isInstalled = $cfgContent.Contains($javaAgentLine)

if ($isInstalled) {
    # ---- UNINSTALL ----
    Write-Host "Dawn patcher is currently installed -- reverting to stock..."
    Stop-Dawn

    if (Test-Path $backupPath) {
        Copy-Item $backupPath $cfgPath -Force
        Write-Host "Restored stock config from local backup."
    } else {
        Write-Host "No backup found -- removing the javaagent line directly instead."
        $newContent = $cfgContent -replace [regex]::Escape("$javaAgentLine`r`n"), ""
        $newContent = $newContent -replace [regex]::Escape("$javaAgentLine`n"), ""
        Set-Content -Path $cfgPath -Value $newContent -NoNewline
    }

    Write-Host ""
    Write-Host "Done. Dawn is back to fully stock. Launch it normally whenever you like."
    Write-Host "Run this script again any time to re-apply the patch."
} else {
    # ---- INSTALL ----
    Write-Host "Installing Dawn patcher..."
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null

    if (-not (Test-Path $agentJar)) {
        Write-Host "Downloading agent jar from the latest release..."
        Invoke-WebRequest -Uri $releaseUrl -OutFile $agentJar
    }

    if (-not (Test-Path $backupPath)) {
        Copy-Item $cfgPath $backupPath
        Write-Host "Backed up original config to: $backupPath"
    }

    $newContent = $cfgContent -replace '(\[JavaOptions\]\r?\n)', "`$1$javaAgentLine`r`n"
    if ($newContent -eq $cfgContent) {
        Write-Host "ERROR: could not find [JavaOptions] section in the cfg -- Dawn's config format may have changed."
        exit 1
    }
    Set-Content -Path $cfgPath -Value $newContent -NoNewline
    Write-Host "Wired the patch into Dawn's launch config."

    # Cosmetic only (T.a() is patched before Dawn ever reads this file, so
    # it's not functionally required) -- keeps the settings file's own
    # recorded width consistent with what the window will actually be.
    if (Test-Path $settingsPath) {
        $settingsContent = Get-Content $settingsPath -Raw
        $newSettings = $settingsContent -replace '"launcherWindowWidthPoints":\d+', "`"launcherWindowWidthPoints`":$targetWidth"
        if ($newSettings -ne $settingsContent) {
            Set-Content -Path $settingsPath -Value $newSettings -NoNewline
        }
    }

    Write-Host ""
    $running = Get-Process "Dawn (Feather)" -ErrorAction SilentlyContinue
    if ($running) {
        Write-Host "Dawn is currently running -- restart it for the patch to take effect."
    } else {
        Write-Host "Done. Launch Dawn normally (double-click, Start Menu, etc.) and the patch will be active."
    }
    Write-Host "Run this script again any time to revert to stock Dawn."
}
