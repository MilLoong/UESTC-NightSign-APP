param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }
$apk = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\rootbridge\build\outputs\apk\debug\rootbridge-debug.apk'))
$helper = Join-Path $PSScriptRoot 'wx-login'
if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Build :rootbridge:assembleDebug first.' }
& $Adb @adbArgs get-state
if ($LASTEXITCODE -ne 0) { throw 'Connect one authorized Android device, or select it with -Serial.' }
& $Adb @adbArgs install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Bridge APK installation failed.' }
& $Adb @adbArgs push $helper /data/local/tmp/uestc-nightsign-wx-login
if ($LASTEXITCODE -ne 0) { throw 'Helper upload failed.' }
$command = 'set -eu; test "$(id -u)" = 0; mkdir -p /data/adb/uestc-nightsign/bin; chmod 700 /data/adb/uestc-nightsign /data/adb/uestc-nightsign/bin; if [ -f /data/adb/uestc-nightsign/bin/wx-login ]; then cp /data/adb/uestc-nightsign/bin/wx-login /data/adb/uestc-nightsign/bin/wx-login.previous; fi; cp /data/local/tmp/uestc-nightsign-wx-login /data/adb/uestc-nightsign/bin/wx-login; chown 0:0 /data/adb/uestc-nightsign/bin/wx-login; chmod 700 /data/adb/uestc-nightsign/bin/wx-login'
& $Adb @adbArgs shell "su -c '$command'"
if ($LASTEXITCODE -ne 0) { throw 'Root helper installation failed. Check device authorization.' }
Write-Output 'Installed. Enable the bridge module in LSPosed, scope only com.tencent.mm, then restart WeChat manually.'
