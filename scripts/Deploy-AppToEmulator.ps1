param(
    [string]$AvdName = "",
    [string]$DeviceId = "",
    [string]$PackageName = "com.uscrooge.app",
    [string]$ActivityName = ".MainActivity",
    [int]$BootTimeoutSeconds = 240,
    [switch]$SkipBuild,
    [switch]$ColdBoot,
    [switch]$NoLaunchApp
)

$ErrorActionPreference = "Stop"

function Get-SdkRoot {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { $_ }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "Android SDK non trovato. Configura ANDROID_SDK_ROOT oppure installa SDK in $env:LOCALAPPDATA\Android\Sdk"
}

function Get-ToolPath {
    param(
        [string]$SdkRoot,
        [string]$RelativePath,
        [string]$FallbackName
    )

    $path = Join-Path $SdkRoot $RelativePath
    if (Test-Path -LiteralPath $path) {
        return $path
    }

    $fallback = Get-Command $FallbackName -ErrorAction SilentlyContinue
    if ($fallback) {
        return $fallback.Source
    }

    throw "Tool non trovato: $path"
}

function Get-EmulatorSerials {
    param([string]$Adb)

    $lines = & $Adb devices
    if (-not $?) {
        throw "adb devices fallito"
    }

    $serials = @()
    foreach ($line in $lines) {
        if ($line -match '^(emulator-\d+)\s+(device|offline)$') {
            $serials += $matches[1]
        }
    }

    return $serials
}

function Get-InstalledAvds {
    param([string]$Emulator)

    $avds = & $Emulator -list-avds
    if (-not $?) {
        throw "Impossibile leggere la lista degli AVD"
    }

    return @($avds | Where-Object { $_ -and $_.Trim() })
}

function Wait-ForEmulatorSerial {
    param(
        [string]$Adb,
        [string[]]$KnownSerials,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $current = Get-EmulatorSerials -Adb $Adb
        $newSerial = $current | Where-Object { $_ -notin $KnownSerials } | Select-Object -First 1
        if ($newSerial) {
            return $newSerial
        }
        Start-Sleep -Seconds 2
    }

    throw "Nessun nuovo emulatore rilevato entro $TimeoutSeconds secondi"
}

function Wait-ForBootCompleted {
    param(
        [string]$Adb,
        [string]$Serial,
        [int]$TimeoutSeconds
    )

    & $Adb -s $Serial wait-for-device | Out-Null

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $bootCompleted = (& $Adb -s $Serial shell getprop sys.boot_completed).Trim()
        $bootAnim = (& $Adb -s $Serial shell getprop init.svc.bootanim).Trim()
        if ($bootCompleted -eq "1" -and $bootAnim -eq "stopped") {
            & $Adb -s $Serial shell input keyevent 82 | Out-Null
            return
        }
        Start-Sleep -Seconds 3
    }

    throw "Emulatore $Serial non avviato entro $TimeoutSeconds secondi"
}

function Test-IsEmulator {
    param(
        [string]$Adb,
        [string]$Serial
    )

    $isQemu = (& $Adb -s $Serial shell getprop ro.kernel.qemu).Trim()
    return $isQemu -eq "1"
}

function Resolve-TargetSerial {
    param(
        [string]$Adb,
        [string]$Emulator,
        [string]$RequestedDeviceId,
        [string]$RequestedAvd,
        [switch]$ColdBoot,
        [int]$TimeoutSeconds
    )

    if ($RequestedDeviceId) {
        return $RequestedDeviceId
    }

    $running = Get-EmulatorSerials -Adb $Adb
    if ($running.Count -gt 0) {
        return $running[0]
    }

    $avdToStart = $RequestedAvd
    if (-not $avdToStart) {
        $installedAvds = Get-InstalledAvds -Emulator $Emulator
        if ($installedAvds.Count -eq 0) {
            throw "Nessun AVD configurato. Crea un emulatore da Android Studio Device Manager."
        }
        if ($installedAvds.Count -gt 1) {
            throw "Più emulatori disponibili: $($installedAvds -join ', '). Specifica -AvdName."
        }
        $avdToStart = $installedAvds[0]
    }

    $known = Get-EmulatorSerials -Adb $Adb
    $args = @("-avd", $avdToStart, "-netdelay", "none", "-netspeed", "full")
    if ($ColdBoot) {
        $args += "-no-snapshot-load"
    }

    Start-Process -FilePath $Emulator -ArgumentList $args | Out-Null
    return Wait-ForEmulatorSerial -Adb $Adb -KnownSerials $known -TimeoutSeconds $TimeoutSeconds
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
Push-Location $repoRoot

try {
    $sdkRoot = Get-SdkRoot
    $adb = Get-ToolPath -SdkRoot $sdkRoot -RelativePath "platform-tools\adb.exe" -FallbackName "adb"
    $emulator = Get-ToolPath -SdkRoot $sdkRoot -RelativePath "emulator\emulator.exe" -FallbackName "emulator"

    $serial = Resolve-TargetSerial -Adb $adb -Emulator $emulator -RequestedDeviceId $DeviceId -RequestedAvd $AvdName -ColdBoot:$ColdBoot -TimeoutSeconds $BootTimeoutSeconds
    Wait-ForBootCompleted -Adb $adb -Serial $serial -TimeoutSeconds $BootTimeoutSeconds

    if (-not (Test-IsEmulator -Adb $adb -Serial $serial)) {
        throw "Il device selezionato ($serial) non è un emulatore Android"
    }

    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug --no-daemon
        if (-not $?) {
            throw "Build debug fallita"
        }
    }

    $apkPath = [System.IO.Path]::GetFullPath("app\build\outputs\apk\debug\app-debug.apk")
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "APK debug non trovata: $apkPath"
    }

    & $adb -s $serial install -r $apkPath
    if (-not $?) {
        throw "Installazione APK fallita su $serial"
    }

    $apiLevelText = (& $adb -s $serial shell getprop ro.build.version.sdk).Trim()
    $apiLevel = 0
    [void][int]::TryParse($apiLevelText, [ref]$apiLevel)
    if ($apiLevel -ge 33) {
        & $adb -s $serial shell pm grant $PackageName android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
    }

    if (-not $NoLaunchApp) {
        & $adb -s $serial shell am force-stop $PackageName | Out-Null
        & $adb -s $serial shell am start -W -n "$PackageName/$ActivityName"
        if (-not $?) {
            throw "Avvio app fallito"
        }
    }

    Write-Host "Deploy completato su emulatore: $serial"
    Write-Host "APK: $apkPath"
}
finally {
    Pop-Location
}
