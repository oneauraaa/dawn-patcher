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

# GitHub release assets are served as application/octet-stream (no charset),
# so `irm | iex` can decode the raw UTF-8 bytes with the wrong code page,
# mangling the multi-byte ASCII-art characters below before this script even
# runs. Storing the art as base64 keeps it ASCII-only in transit -- immune to
# that mis-decoding -- and we decode it explicitly as UTF-8 here. We also
# force the console's output encoding to UTF-8 so the decoded glyphs render
# correctly regardless of the host's default code page.
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$artB64 = "IOKjh+Kjv+KgmOKjv+Kjv+Kjv+Khv+Khv+Kjn+Kjn+Kin+Kin+KineKgteKhneKjv+Khv+KiguKjvOKjv+Kjt+KjjOKgqeKhq+Khu+KjneKgueKiv+Kjv+Kjtwog4qGG4qO/4qOG4qCx4qOd4qG14qOd4qKF4qCZ4qO/4qKV4qKV4qKV4qKV4qKd4qOl4qKS4qCF4qO/4qO/4qO/4qG/4qOz4qOM4qCq4qGq4qOh4qKR4qKd4qOHCiDioYbio7/io7/io6bioLnio7Pio7Pio5XiooXioIjiopfiopXiopXiopXiopXiopXioojioobioJ/ioIvioInioIHioInioInioIHioIjioLziopDiopXior0KIOKhl+KisOKjtuKjtuKjpuKjneKineKileKileKgheKhhuKileKileKileKileKileKjtOKgj+KjoOKhtuKgm+KhieKhieKhm+KituKjpuKhgOKgkOKjleKilQog4qGd4qGE4qK74qKf4qO/4qO/4qO34qOV4qOV4qOF4qO/4qOU4qOV4qO14qO14qO/4qO/4qKg4qO/4qKg4qOu4qGI4qOM4qCo4qCF4qC54qO34qGA4qKx4qKVCiDioZ3iobXioJ/ioIjiooDio4Dio4DioYDioInior/io7/io7/io7/io7/io7/io7/io7/io7zio7/ioojioYvioLTior/ioZ/io6HioYfio7/ioYfioYDiopUKIOKhneKggeKjoOKjvuKgn+KhieKhieKhieKgu+KjpuKju+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjp+KguOKjv+KjpuKjpeKjv+Khh+Khv+KjsOKil+KihAog4qCB4qKw4qO/4qGP4qO04qOM4qCI4qOM4qCh4qCI4qK74qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qOs4qOJ4qOJ4qOB4qOE4qKW4qKV4qKV4qKVCiDioYDiorvio7/ioYfiopnioIHioLTior/ioZ/io6HioYbio7/io7/io7/io7/io7/io7/io7/io7/io7/io7/io7/io7/io7/io7/io7/io7fio7Xio7Xio78KIOKhu+KjhOKju+Kjv+KjjOKgmOKiv+Kjt+KjpeKjv+Kgh+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kgm+Kgu+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjvwog4qO34qKE4qC74qO/4qOf4qC/4qCm4qCN4qCJ4qOh4qO+4qO/4qO/4qO/4qO/4qO/4qO/4qK44qO/4qOm4qCZ4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qCfCiDioZXioZHio5Hio4jio7viopfiop/iop7iop3io7vio7/io7/io7/io7/io7/io7/io7/ioLjio7/ioL/ioIPio7/io7/io7/io7/io7/io7/iob/ioIHio6AKIOKhneKhteKhiOKin+KileKileKileKileKjteKjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+Kjv+KjtuKjtuKjv+Kjv+Kjv+Kjv+Kjv+Kgv+Kgi+KjgOKjiOKgmQog4qGd4qG14qGV4qGA4qCR4qCz4qC/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qO/4qC/4qCb4qKJ4qGg4qGy4qGr4qGq4qGq4qGj"
$art = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($artB64))

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
Write-Host $art
Write-Host ""
Write-Host " Dawn Client Patcher | oneauraaa/dawn-patcher | oneaura.lol"
Write-Host ""

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
