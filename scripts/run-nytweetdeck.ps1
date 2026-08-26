param(
    [switch]$NoBrowser,
    [switch]$ExitAfterReady
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $scriptRoot 'NyTweetDeck.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "NyTweetDeck.jarが見つかりません: $jarPath"
}
if ($null -eq (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java 21以上をインストールしてから再実行してください。'
}

$process = Start-Process -FilePath 'java' -ArgumentList '-jar', $jarPath -PassThru -NoNewWindow
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt += 1) {
        if ($process.HasExited) {
            throw "NyTweetDeckが起動前に終了しました。終了コード: $($process.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/api/v1/system/status' -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) {
                $ready = $true
                break
            }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $ready) {
        throw 'NyTweetDeckの起動が30秒以内に完了しませんでした。'
    }
    if (-not $NoBrowser) {
        Start-Process 'http://127.0.0.1:18080'
    }
    if ($ExitAfterReady) {
        return
    }
    Wait-Process -Id $process.Id
}
finally {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id
    }
}
