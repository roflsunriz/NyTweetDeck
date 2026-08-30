param(
    [string]$JarPath = "",
    [string]$ChromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe",
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

function Get-AvailableLoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$targetDirectory = Join-Path $repositoryRoot 'target'
if (-not $SkipBuild) {
    Push-Location $repositoryRoot
    try {
        & mvn -q '-Dmaven.test.skip=true' package
        if ($LASTEXITCODE -ne 0) { throw 'READMEキャプチャ用JARのビルドに失敗しました。' }
    }
    finally {
        Pop-Location
    }
}
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $version = (& mvn -q help:evaluate '-Dexpression=project.version' '-DforceStdout').Trim()
    $JarPath = Join-Path $repositoryRoot "target\nytweetdeck-$version.jar"
}
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$resolvedChrome = (Resolve-Path -LiteralPath $ChromePath).Path
$captureId = [Guid]::NewGuid().ToString('N')
$profileDirectory = Join-Path $targetDirectory "readme-capture-profile-$captureId"
$settingsStorePath = Join-Path $profileDirectory 'settings.json'
$httpPort = Get-AvailableLoopbackPort
$cdpPort = Get-AvailableLoopbackPort
while ($cdpPort -eq $httpPort) { $cdpPort = Get-AvailableLoopbackPort }
[IO.Directory]::CreateDirectory($profileDirectory) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $repositoryRoot 'docs\images')) | Out-Null
$javaProcess = $null
$chromeProcess = $null
$previousApplicationUrl = $env:NYTWEETDECK_URL
$previousCdpPort = $env:CHROME_CDP_PORT

try {
    $javaProcess = Start-Process -FilePath 'java' -ArgumentList @(
        '-jar', $resolvedJar, "--server.port=$httpPort",
        "--nytweetdeck.settings.store-path=$settingsStorePath"
    ) -WorkingDirectory $repositoryRoot -WindowStyle Hidden `
      -RedirectStandardOutput (Join-Path $targetDirectory "readme-server-$captureId.log") `
      -RedirectStandardError (Join-Path $targetDirectory "readme-server-error-$captureId.log") -PassThru

    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($javaProcess.HasExited) { throw "NyTweetDeckが準備完了前に終了しました: $($javaProcess.ExitCode)" }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$httpPort/api/v1/system/status" -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        }
        catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw 'NyTweetDeckが20秒以内に起動しませんでした。' }

    $chromeProcess = Start-Process -FilePath $resolvedChrome -ArgumentList @(
        '--headless=new', '--disable-gpu', '--no-first-run', "--remote-debugging-port=$cdpPort",
        "--user-data-dir=$profileDirectory", 'about:blank'
    ) -WindowStyle Hidden -PassThru
    $cdpReady = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($chromeProcess.HasExited) { throw "Chromeが準備完了前に終了しました: $($chromeProcess.ExitCode)" }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$cdpPort/json/version" -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $cdpReady = $true; break }
        }
        catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $cdpReady) { throw 'Chromeのデバッグ接続が10秒以内に準備できませんでした。' }
    $env:NYTWEETDECK_URL = "http://127.0.0.1:$httpPort"
    $env:CHROME_CDP_PORT = [string]$cdpPort
    Push-Location (Join-Path $repositoryRoot 'frontend')
    try {
        bun run capture:readme
        if ($LASTEXITCODE -ne 0) { throw 'README画像のキャプチャに失敗しました。' }
    }
    finally { Pop-Location }
}
finally {
    if ($null -eq $previousApplicationUrl) { Remove-Item Env:NYTWEETDECK_URL -ErrorAction SilentlyContinue }
    else { $env:NYTWEETDECK_URL = $previousApplicationUrl }
    if ($null -eq $previousCdpPort) { Remove-Item Env:CHROME_CDP_PORT -ErrorAction SilentlyContinue }
    else { $env:CHROME_CDP_PORT = $previousCdpPort }
    if ($null -ne $javaProcess) { Stop-Process -Id $javaProcess.Id -Force -ErrorAction SilentlyContinue }
    $profileChromeIds = @(Get-CimInstance Win32_Process -Filter "Name = 'chrome.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*$profileDirectory*" } | Select-Object -ExpandProperty ProcessId)
    if ($profileChromeIds.Count -gt 0) { Stop-Process -Id $profileChromeIds -Force -ErrorAction SilentlyContinue }
    elseif ($null -ne $chromeProcess) { Stop-Process -Id $chromeProcess.Id -Force -ErrorAction SilentlyContinue }
    $absoluteTarget = [IO.Path]::GetFullPath($targetDirectory).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $absoluteProfile = [IO.Path]::GetFullPath($profileDirectory)
    if ($absoluteProfile.StartsWith($absoluteTarget, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $absoluteProfile -Recurse -Force -ErrorAction SilentlyContinue
    }
}
