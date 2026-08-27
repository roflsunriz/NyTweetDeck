param(
    [string]$JarPath = "",
    [string]$ChromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe",
    [ValidateRange(1024, 65535)]
    [int]$HttpPort = 18080,
    [ValidateRange(1024, 65535)]
    [int]$CdpPort = 9222
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $version = (& mvn -q help:evaluate '-Dexpression=project.version' '-DforceStdout').Trim()
    $JarPath = Join-Path $repositoryRoot "target\nytweetdeck-$version.jar"
}
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$resolvedChrome = (Resolve-Path -LiteralPath $ChromePath).Path
$targetDirectory = Join-Path $repositoryRoot 'target'
$profileDirectory = Join-Path $targetDirectory "cdp-profile-verification-$CdpPort"
$existingChrome = @(Get-Process chrome -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
$javaProcess = $null
$previousApplicationUrl = $env:NYTWEETDECK_URL
$previousCdpPort = $env:CHROME_CDP_PORT

try {
    $javaProcess = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $resolvedJar, "--server.port=$HttpPort") `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $targetDirectory 'ui-server.log') `
        -RedirectStandardError (Join-Path $targetDirectory 'ui-server-error.log') `
        -PassThru

    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing `
                -Uri "http://127.0.0.1:$HttpPort/api/v1/system/status" -TimeoutSec 1
            if ($response.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $ready) {
        throw 'NyTweetDeckが20秒以内に起動しませんでした。'
    }

    Start-Process -FilePath $resolvedChrome `
        -ArgumentList @(
            '--headless=new',
            '--disable-gpu',
            '--no-first-run',
            "--remote-debugging-port=$CdpPort",
            "--user-data-dir=$profileDirectory",
            'about:blank'
        ) `
        -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 2
    $env:NYTWEETDECK_URL = "http://127.0.0.1:$HttpPort"
    $env:CHROME_CDP_PORT = [string]$CdpPort
    Push-Location (Join-Path $repositoryRoot 'frontend')
    try {
        bun run verify:ui
        if ($LASTEXITCODE -ne 0) {
            $browserExitCode = $LASTEXITCODE
            throw "ブラウザUI検証が終了コード${browserExitCode}で失敗しました。"
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousApplicationUrl) {
        Remove-Item Env:NYTWEETDECK_URL -ErrorAction SilentlyContinue
    } else {
        $env:NYTWEETDECK_URL = $previousApplicationUrl
    }
    if ($null -eq $previousCdpPort) {
        Remove-Item Env:CHROME_CDP_PORT -ErrorAction SilentlyContinue
    } else {
        $env:CHROME_CDP_PORT = $previousCdpPort
    }
    if ($null -ne $javaProcess) {
        Stop-Process -Id $javaProcess.Id -Force -ErrorAction SilentlyContinue
    }
    $newChrome = @(Get-Process chrome -ErrorAction SilentlyContinue | Where-Object {
        $_.Id -notin $existingChrome
    } | Select-Object -ExpandProperty Id)
    if ($newChrome.Count -gt 0) {
        Stop-Process -Id $newChrome -Force -ErrorAction SilentlyContinue
    }
}
