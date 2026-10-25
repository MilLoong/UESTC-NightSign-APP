param(
    [Parameter(Mandatory = $true)]
    [string]$DebuggerPath
)

$ErrorActionPreference = 'Stop'
$debugger = (Get-Item -LiteralPath $DebuggerPath).FullName
if (-not (Test-Path -LiteralPath (Join-Path $debugger 'src/index.ts'))) {
    throw 'WMPFDebugger source directory not found'
}
$node = (Get-Command node.exe -ErrorAction Stop).Source
foreach ($port in @(9421, 62000, 18765)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use; stop the existing local broker/debugger first"
    }
}
$local = Join-Path $PSScriptRoot '.local'
New-Item -ItemType Directory -Path $local -ErrorAction SilentlyContinue | Out-Null
$outputLog = Join-Path $local 'debugger.log'
$errorLog = Join-Path $local 'debugger-errors.log'
$hook = Start-Process -FilePath $node -ArgumentList @('--import', 'tsx', 'src/index.ts') `
    -WorkingDirectory $debugger -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($hook.HasExited) { throw 'Debugger exited; check .local/debugger-errors.log' }
        if ((Test-Path -LiteralPath $outputLog) -and
            (Select-String -LiteralPath $outputLog -Pattern '\[frida\] script loaded' -Quiet)) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Debugger did not become ready within 30 seconds' }
    Write-Host 'Re-enter the target mini-program after the debugger is ready.'
    & $node (Join-Path $PSScriptRoot 'server.mjs')
} finally {
    if (-not $hook.HasExited) { Stop-Process -Id $hook.Id -ErrorAction SilentlyContinue }
}
