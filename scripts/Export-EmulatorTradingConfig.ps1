param(
    [string]$PackageName = "com.uscrooge.app",
    [string]$DeviceId = "",
    [string]$OutputPath = ".local\trading_config.preferences_pb"
)

$ErrorActionPreference = "Stop"

function Get-AdbCommand {
    $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $adb)) {
        throw "adb non trovato in '$adb'"
    }
    return $adb
}

$adb = Get-AdbCommand

$adbArgs = @()
if ($DeviceId) {
    $adbArgs += @("-s", $DeviceId)
}

$parent = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

$resolvedOutputPath = [System.IO.Path]::GetFullPath($OutputPath)

$bytes = & $adb @adbArgs exec-out run-as $PackageName cat files/datastore/trading_config.preferences_pb
if (-not $?) {
    throw "Impossibile leggere DataStore da emulator per package '$PackageName'"
}

[System.IO.File]::WriteAllBytes($resolvedOutputPath, $bytes)

"Config esportata in: $resolvedOutputPath"
