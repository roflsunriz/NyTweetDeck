param(
    [string]$JarPath = "",
    [switch]$NoBrowser,
    [switch]$ExitAfterReady
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $scriptRoot 'NyTweetDeck.jar'
}
$resolvedJarPath = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedJarPath)) {
    throw "NyTweetDeck.jarが見つかりません: $resolvedJarPath"
}
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $javaCommand) {
    throw 'Java 17、21、25のいずれかをインストールしてから再実行してください。'
}
$versionProbeInfo = [System.Diagnostics.ProcessStartInfo]::new()
$versionProbeInfo.FileName = $javaCommand.Source
$versionProbeInfo.Arguments = '-version'
$versionProbeInfo.UseShellExecute = $false
$versionProbeInfo.RedirectStandardError = $true
$versionProbeInfo.RedirectStandardOutput = $true
$versionProbe = [System.Diagnostics.Process]::Start($versionProbeInfo)
$javaVersionOutput = $versionProbe.StandardError.ReadToEnd() + $versionProbe.StandardOutput.ReadToEnd()
$versionProbe.WaitForExit()
$javaVersionLine = ($javaVersionOutput -split "`r?`n")[0]
if ($versionProbe.ExitCode -ne 0 -or $javaVersionLine -notmatch 'version "(?:1\.)?([0-9]+)') {
    throw 'Javaのバージョンを確認できませんでした。'
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 17) {
    throw "Java 17以上が必要です。現在のメジャーバージョン: $javaMajor"
}

$javaArguments = @('-jar', ('"{0}"' -f $resolvedJarPath))
$accessUrl = 'http://127.0.0.1:18080'
$localData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
$domainConfigPath = Join-Path $localData 'NyTweetDeck\local-domain.json'
if (Test-Path -LiteralPath $domainConfigPath) {
    $domainConfig = Get-Content -Raw -LiteralPath $domainConfigPath | ConvertFrom-Json
    if ($domainConfig.schemaVersion -ne 1 `
            -or $domainConfig.host -ne 'ny.tweetdeck.com' `
            -or $domainConfig.httpsPort -ne 443 `
            -or $domainConfig.httpPort -ne 18080 `
            -or -not (Test-Path -LiteralPath $domainConfig.keyStorePath) `
            -or [string]::IsNullOrWhiteSpace([string]$domainConfig.keyStorePassword)) {
        throw "ローカルドメイン設定が不正です: $domainConfigPath"
    }
    $javaArguments += @(
        '--server.port=443',
        '--server.ssl.enabled=true',
        ('--server.ssl.key-store="{0}"' -f $domainConfig.keyStorePath),
        ('--server.ssl.key-store-password={0}' -f $domainConfig.keyStorePassword),
        '--server.ssl.key-store-type=PKCS12',
        '--nytweetdeck.http.port=18080'
    )
    $accessUrl = 'https://ny.tweetdeck.com'
}
$process = Start-Process `
    -FilePath 'java' `
    -ArgumentList ($javaArguments -join ' ') `
    -PassThru `
    -NoNewWindow
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
        Start-Process $accessUrl
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
