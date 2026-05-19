param(
    [string]$PackageName = "com.uscrooge.app",
    [string]$ActivityName = ".MainActivity",
    [string]$DeviceId = "",
    [string]$LocalConfigPath = ".local\kraken-test-config.properties",
    [switch]$BuildApk
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

function Parse-ConfigFile {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Config locale non trovata: $Path"
    }

    $lines = Get-Content -LiteralPath $Path
    $map = @{}
    foreach ($line in $lines) {
        if (-not $line -or $line.Trim().StartsWith("#")) {
            continue
        }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) {
            continue
        }
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim()
        $map[$k] = $v
    }

    if (-not $map.ContainsKey("KRAKEN_API_KEY") -or -not $map.ContainsKey("KRAKEN_API_SECRET")) {
        throw "La config deve contenere KRAKEN_API_KEY e KRAKEN_API_SECRET"
    }

    return $map
}

function Get-DataStoreBytes {
    param(
        [string]$Adb,
        [string[]]$AdbArgs,
        [string]$Package
    )

    $tempPath = Join-Path $env:TEMP "opencode-datastore-export.bin"
    $argList = @($AdbArgs + @("exec-out", "run-as", $Package, "cat", "files/datastore/trading_config.preferences_pb"))
    $proc = Start-Process -FilePath $Adb -ArgumentList $argList -NoNewWindow -PassThru -Wait -RedirectStandardOutput $tempPath
    if ($proc.ExitCode -ne 0) {
        throw "Impossibile esportare trading_config.preferences_pb"
    }

    $bytes = [System.IO.File]::ReadAllBytes($tempPath)
    Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
    return $bytes
}

function Get-WireLength {
    param([byte[]]$Bytes, [int]$Offset)

    $result = 0
    $shift = 0
    $index = $Offset
    while ($index -lt $Bytes.Length) {
        $b = $Bytes[$index]
        $result = $result -bor (($b -band 0x7F) -shl $shift)
        $index++
        if (($b -band 0x80) -eq 0) {
            return @($result, $index)
        }
        $shift += 7
    }

    throw "Varint incompleto durante parsing"
}

function Decode-AsciiSlice {
    param([byte[]]$Bytes, [int]$Start)

    $sb = New-Object System.Text.StringBuilder
    for ($i = $Start; $i -lt $Bytes.Length; $i++) {
        $b = $Bytes[$i]
        if ($b -eq 10 -or $b -eq 13) {
            break
        }
        if ($b -ge 32 -and $b -le 126) {
            [void]$sb.Append([char]$b)
        } else {
            break
        }
    }
    return $sb.ToString()
}

function Patch-LengthDelimitedField {
    param(
        [byte[]]$Bytes,
        [string]$FieldName,
        [string]$NewValue
    )

    $nameBytes = [System.Text.Encoding]::ASCII.GetBytes($FieldName)
    for ($i = 0; $i -le $Bytes.Length - $nameBytes.Length; $i++) {
        $match = $true
        for ($j = 0; $j -lt $nameBytes.Length; $j++) {
            if ($Bytes[$i + $j] -ne $nameBytes[$j]) {
                $match = $false
                break
            }
        }

        if (-not $match) {
            continue
        }

        $tagIndex = $i + $nameBytes.Length
        if ($tagIndex -ge $Bytes.Length -or $Bytes[$tagIndex] -ne 0x12) {
            continue
        }

        $lenInfo = Get-WireLength -Bytes $Bytes -Offset ($tagIndex + 1)
        $oldLength = [int]$lenInfo[0]
        $valueStart = [int]$lenInfo[1]
        $valueEnd = $valueStart + $oldLength
        if ($valueEnd -gt $Bytes.Length) {
            throw "Valore oltre i limiti durante patch di $FieldName"
        }

        $oldValue = Decode-AsciiSlice -Bytes $Bytes -Start $valueStart
        $prefixLength = 0
        while ($prefixLength -lt $oldLength) {
            $b = $Bytes[$valueStart + $prefixLength]
            if ($b -ge 32 -and $b -le 126) {
                $prefixLength++
            } else {
                break
            }
        }

        $prefix = if ($prefixLength -gt 0) {
            [System.Text.Encoding]::ASCII.GetString($Bytes, $valueStart, $prefixLength)
        } else {
            ""
        }

        $updatedValue = "$prefix$NewValue"
        $updatedValueBytes = [System.Text.Encoding]::ASCII.GetBytes($updatedValue)
        $newLen = [byte[]]@($updatedValueBytes.Length)

        $headLength = $tagIndex + 1
        $tailLength = $Bytes.Length - $valueEnd

        $stream = New-Object System.IO.MemoryStream
        $stream.Write($Bytes, 0, $headLength)
        $stream.Write($newLen, 0, $newLen.Length)
        $stream.Write($updatedValueBytes, 0, $updatedValueBytes.Length)
        if ($tailLength -gt 0) {
            $stream.Write($Bytes, $valueEnd, $tailLength)
        }

        return ,$stream.ToArray()
    }

    throw "Campo '$FieldName' non trovato nel DataStore"
}

function Push-DataStoreFile {
    param(
        [string]$Adb,
        [string[]]$AdbArgs,
        [string]$Package,
        [byte[]]$Bytes
    )

    $localTemp = Join-Path $env:TEMP "trading_config.updated.pb"
    [System.IO.File]::WriteAllBytes($localTemp, $Bytes)

    $remoteTemp = "/data/local/tmp/trading_config.updated.pb"
    & $Adb @AdbArgs push "$localTemp" "$remoteTemp" | Out-Null
    if (-not $?) {
        Remove-Item -LiteralPath $localTemp -Force -ErrorAction SilentlyContinue
        throw "Push del file DataStore fallito"
    }

    & $Adb @AdbArgs shell "run-as $Package cp $remoteTemp files/datastore/trading_config.preferences_pb"
    if (-not $?) {
        Remove-Item -LiteralPath $localTemp -Force -ErrorAction SilentlyContinue
        throw "Copia DataStore nel sandbox app fallita"
    }

    & $Adb @AdbArgs shell rm "$remoteTemp" | Out-Null
    Remove-Item -LiteralPath $localTemp -Force -ErrorAction SilentlyContinue
}

function Get-ApkPath {
    return [System.IO.Path]::GetFullPath("app\build\outputs\apk\debug\app-debug.apk")
}

$adb = Get-AdbCommand
$adbArgs = @()
if ($DeviceId) {
    $adbArgs += @("-s", $DeviceId)
}

$config = Parse-ConfigFile -Path $LocalConfigPath

if ($BuildApk) {
    $jdk17 = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
    if (Test-Path -LiteralPath $jdk17) {
        $env:JAVA_HOME = $jdk17
        $env:Path = "$jdk17\bin;$env:Path"
    }
    & .\gradlew.bat :app:assembleDebug --no-daemon
    if (-not $?) {
        throw "Build APK fallita"
    }
}

$apkPath = Get-ApkPath
if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "APK non trovata: $apkPath"
}

& $adb @adbArgs install -r "$apkPath"
if (-not $?) {
    throw "Installazione APK fallita"
}

$bytes = Get-DataStoreBytes -Adb $adb -AdbArgs $adbArgs -Package $PackageName
$bytes = Patch-LengthDelimitedField -Bytes $bytes -FieldName "kraken_api_key" -NewValue $config["KRAKEN_API_KEY"]
$bytes = Patch-LengthDelimitedField -Bytes $bytes -FieldName "kraken_api_secret" -NewValue $config["KRAKEN_API_SECRET"]
Push-DataStoreFile -Adb $adb -AdbArgs $adbArgs -Package $PackageName -Bytes $bytes

& $adb @adbArgs logcat -c
& $adb @adbArgs shell am force-stop $PackageName
Start-Sleep -Milliseconds 600

$component = "$PackageName/$ActivityName"
& $adb @adbArgs shell am start -W -n $component
if (-not $?) {
    throw "Avvio app fallito"
}

Start-Sleep -Seconds 6

$logs = & $adb @adbArgs logcat -d
$hasBalance = $false
$hasTradeBalance = $false
$hasFatal = $false

foreach ($line in $logs) {
    if ($line -match "/0/private/Balance" -and $line -match "<-- 200") {
        $hasBalance = $true
    }
    if ($line -match "/0/private/TradeBalance" -and $line -match "<-- 200") {
        $hasTradeBalance = $true
    }
    if ($line -match "FATAL EXCEPTION" -and $line -match $PackageName) {
        $hasFatal = $true
    }
}

"Kraken Balance 200: $hasBalance"
"Kraken TradeBalance 200: $hasTradeBalance"
"Fatal crash detected: $hasFatal"

if (-not $hasBalance -or -not $hasTradeBalance -or $hasFatal) {
    throw "Smoke test Kraken non superato"
}

"Smoke test Kraken completato con successo."
