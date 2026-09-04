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

# Overwrites the current console line instead of scrolling, so the whole run
# reads as one live status update. Falls back to a plain line if the host
# has no real console to move a cursor on (e.g. output is redirected).
function Write-Status {
    param([Parameter(Mandatory)][string]$Text)
    try {
        $width = [Console]::WindowWidth
        if ($width -lt 10) { $width = 80 }
        [Console]::SetCursorPosition(0, [Console]::CursorTop)
        Write-Host (" " * ($width - 1)) -NoNewline
        [Console]::SetCursorPosition(0, [Console]::CursorTop)
        Write-Host $Text -NoNewline
    } catch {
        Write-Host $Text
    }
}

function Complete-Status {
    param([Parameter(Mandatory)][string]$Text)
    Write-Status $Text
    Write-Host ""
}

function Show-CheckingAnimation {
    param([int]$DurationMs = 900, [int]$FrameMs = 220)
    $dotFrames = @(".", "..", "...")
    $elapsed = 0
    $i = 0
    while ($elapsed -lt $DurationMs) {
        Write-Status "Checking$($dotFrames[$i % $dotFrames.Count])"
        Start-Sleep -Milliseconds $FrameMs
        $elapsed += $FrameMs
        $i++
    }
}

function Stop-Dawn {
    $running = Get-Process "Dawn (Feather)" -ErrorAction SilentlyContinue
    if ($running) {
        Write-Status "Stopping running Dawn process(es)..."
        $running | Stop-Process -Force -Confirm:$false
        Start-Sleep -Milliseconds 500
    }
}

Clear-Host
Write-Host @"
 ⣇⣿⠘⣿⣿⣿⡿⡿⣟⣟⢟⢟⢝⠵⡝⣿⡿⢂⣼⣿⣷⣌⠩⡫⡻⣝⠹⢿⣿⣷
 ⡆⣿⣆⠱⣝⡵⣝⢅⠙⣿⢕⢕⢕⢕⢝⣥⢒⠅⣿⣿⣿⡿⣳⣌⠪⡪⣡⢑⢝⣇
 ⡆⣿⣿⣦⠹⣳⣳⣕⢅⠈⢗⢕⢕⢕⢕⢕⢈⢆⠟⠋⠉⠁⠉⠉⠁⠈⠼⢐⢕⢽
 ⡗⢰⣶⣶⣦⣝⢝⢕⢕⠅⡆⢕⢕⢕⢕⢕⣴⠏⣠⡶⠛⡉⡉⡛⢶⣦⡀⠐⣕⢕
 ⡝⡄⢻⢟⣿⣿⣷⣕⣕⣅⣿⣔⣕⣵⣵⣿⣿⢠⣿⢠⣮⡈⣌⠨⠅⠹⣷⡀⢱⢕
 ⡝⡵⠟⠈⢀⣀⣀⡀⠉⢿⣿⣿⣿⣿⣿⣿⣿⣼⣿⢈⡋⠴⢿⡟⣡⡇⣿⡇⡀⢕
 ⡝⠁⣠⣾⠟⡉⡉⡉⠻⣦⣻⣿⣿⣿⣿⣿⣿⣿⣿⣧⠸⣿⣦⣥⣿⡇⡿⣰⢗⢄
 ⠁⢰⣿⡏⣴⣌⠈⣌⠡⠈⢻⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣬⣉⣉⣁⣄⢖⢕⢕⢕
 ⡀⢻⣿⡇⢙⠁⠴⢿⡟⣡⡆⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣷⣵⣵⣿
 ⡻⣄⣻⣿⣌⠘⢿⣷⣥⣿⠇⣿⣿⣿⣿⣿⣿⠛⠻⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿
 ⣷⢄⠻⣿⣟⠿⠦⠍⠉⣡⣾⣿⣿⣿⣿⣿⣿⢸⣿⣦⠙⣿⣿⣿⣿⣿⣿⣿⣿⠟
 ⡕⡑⣑⣈⣻⢗⢟⢞⢝⣻⣿⣿⣿⣿⣿⣿⣿⠸⣿⠿⠃⣿⣿⣿⣿⣿⣿⡿⠁⣠
 ⡝⡵⡈⢟⢕⢕⢕⢕⣵⣿⣿⣿⣿⣿⣿⣿⣿⣿⣶⣶⣿⣿⣿⣿⣿⠿⠋⣀⣈⠙
 ⡝⡵⡕⡀⠑⠳⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠿⠛⢉⡠⡲⡫⡪⡪⡣

 Dawn Client Patcher | oneauraaa/dawn-patcher | oneaura.lol

"@

Show-CheckingAnimation

if (-not (Test-Path $cfgPath)) {
    Complete-Status "Dawn config not found."
    Write-Host "  Checked: $cfgPath"
    Write-Host "  Is Dawn installed? This script only supports the standard per-user install location."
    exit 1
}

$cfgContent    = Get-Content $cfgPath -Raw
$javaAgentLine = "java-options=-javaagent:$agentJar"
$isInstalled   = $cfgContent.Contains($javaAgentLine)

try {
    if ($isInstalled) {
        # ---- UNINSTALL ----
        Write-Status "Installation found, uninstalling patch..."
        Stop-Dawn

        if (Test-Path $backupPath) {
            Copy-Item $backupPath $cfgPath -Force
            Write-Status "Restored stock config from local backup..."
        } else {
            Write-Status "No backup found, removing the javaagent line directly..."
            $newContent = $cfgContent -replace [regex]::Escape("$javaAgentLine`r`n"), ""
            $newContent = $newContent -replace [regex]::Escape("$javaAgentLine`n"), ""
            Set-Content -Path $cfgPath -Value $newContent -NoNewline
        }

        Complete-Status "Patch uninstalled!"
        Write-Host ""
        Write-Host "Dawn is back to fully stock. Launch it normally whenever you like."
        Write-Host "Run this script again any time to re-apply the patch."
    } else {
        # ---- INSTALL ----
        Write-Status "Installation not found, installing patch..."
        New-Item -ItemType Directory -Force -Path $installDir | Out-Null

        if (-not (Test-Path $agentJar)) {
            Write-Status "Installation not found, downloading patch..."
            Invoke-WebRequest -Uri $releaseUrl -OutFile $agentJar
        }

        if (-not (Test-Path $backupPath)) {
            Copy-Item $cfgPath $backupPath
            Write-Status "Backed up original config..."
        }

        $newContent = $cfgContent -replace '(\[JavaOptions\]\r?\n)', "`$1$javaAgentLine`r`n"
        if ($newContent -eq $cfgContent) {
            throw "could not find [JavaOptions] section in the cfg -- Dawn's config format may have changed."
        }
        Set-Content -Path $cfgPath -Value $newContent -NoNewline
        Write-Status "Wiring patch into Dawn's launch config..."

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

        Complete-Status "Patch installed!"
        Write-Host ""
        $running = Get-Process "Dawn (Feather)" -ErrorAction SilentlyContinue
        if ($running) {
            Write-Host "Dawn is currently running -- restart it for the patch to take effect."
        } else {
            Write-Host "Launch Dawn normally (double-click, Start Menu, etc.) and the patch will be active."
        }
        Write-Host "Run this script again any time to revert to stock Dawn."
    }
} catch {
    if ($isInstalled) {
        Complete-Status "Patch uninstall failed!"
    } else {
        Complete-Status "Patch install failed!"
    }
    Write-Host ""
    Write-Host "Error: $($_.Exception.Message)"
    exit 1
}
