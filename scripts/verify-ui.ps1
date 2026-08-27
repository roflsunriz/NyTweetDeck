param(
    [string]$JarPath = "",
    [string]$ChromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe"
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
$profileDirectory = Join-Path $targetDirectory 'cdp-profile-verification'
$existingChrome = @(Get-Process chrome -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
$javaProcess = $null

try {
    $javaProcess = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $resolvedJar) `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $targetDirectory 'ui-server.log') `
        -RedirectStandardError (Join-Path $targetDirectory 'ui-server-error.log') `
        -PassThru

    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing `
                -Uri 'http://127.0.0.1:18080/api/v1/system/status' -TimeoutSec 1
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
            '--remote-debugging-port=9222',
            "--user-data-dir=$profileDirectory",
            'about:blank'
        ) `
        -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 2
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
