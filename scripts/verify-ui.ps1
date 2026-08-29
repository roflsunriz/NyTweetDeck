param(
    [string]$JarPath = "",
    [string]$ChromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe",
    [ValidateScript({ $_ -eq 0 -or ($_ -ge 1024 -and $_ -le 65535) })]
    [int]$HttpPort = 0,
    [ValidateScript({ $_ -eq 0 -or ($_ -ge 1024 -and $_ -le 65535) })]
    [int]$CdpPort = 0
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

function Assert-LoopbackPortAvailable([int]$Port, [string]$Purpose) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Start()
    }
    catch {
        throw "$Purpose ポート $Port は既に使用されています。別のポートを指定してください。"
    }
    finally {
        $listener.Stop()
    }
}

$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $version = (& mvn -q help:evaluate '-Dexpression=project.version' '-DforceStdout').Trim()
    $JarPath = Join-Path $repositoryRoot "target\nytweetdeck-$version.jar"
}
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$resolvedChrome = (Resolve-Path -LiteralPath $ChromePath).Path
$targetDirectory = Join-Path $repositoryRoot 'target'
$verificationId = [Guid]::NewGuid().ToString('N')
$profileDirectory = Join-Path $targetDirectory "cdp-profile-verification-$verificationId"
$settingsStorePath = Join-Path $profileDirectory 'settings.json'
$effectiveHttpPort = if ($HttpPort -eq 0) { Get-AvailableLoopbackPort } else { $HttpPort }
$effectiveCdpPort = if ($CdpPort -eq 0) { Get-AvailableLoopbackPort } else { $CdpPort }
while ($effectiveCdpPort -eq $effectiveHttpPort) {
    $effectiveCdpPort = Get-AvailableLoopbackPort
}
Assert-LoopbackPortAvailable $effectiveHttpPort 'HTTP'
Assert-LoopbackPortAvailable $effectiveCdpPort 'CDP'
[IO.Directory]::CreateDirectory($profileDirectory) | Out-Null
$javaProcess = $null
$chromeProcess = $null
$previousApplicationUrl = $env:NYTWEETDECK_URL
$previousCdpPort = $env:CHROME_CDP_PORT

try {
    $javaProcess = Start-Process -FilePath 'java' `
        -ArgumentList @(
            '-jar',
            $resolvedJar,
            "--server.port=$effectiveHttpPort",
            "--nytweetdeck.settings.store-path=$settingsStorePath"
        ) `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $targetDirectory "ui-server-$verificationId.log") `
        -RedirectStandardError (Join-Path $targetDirectory "ui-server-error-$verificationId.log") `
        -PassThru

    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        if ($javaProcess.HasExited) {
            throw "NyTweetDeck検証プロセスが準備完了前に終了しました。終了コード: $($javaProcess.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing `
                -Uri "http://127.0.0.1:$effectiveHttpPort/api/v1/system/status" -TimeoutSec 1
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

    $chromeProcess = Start-Process -FilePath $resolvedChrome `
        -ArgumentList @(
            '--headless=new',
            '--disable-gpu',
            '--no-first-run',
            "--remote-debugging-port=$effectiveCdpPort",
            "--user-data-dir=$profileDirectory",
            'about:blank'
        ) `
        -WindowStyle Hidden `
        -PassThru
    Start-Sleep -Seconds 2
    $env:NYTWEETDECK_URL = "http://127.0.0.1:$effectiveHttpPort"
    $env:CHROME_CDP_PORT = [string]$effectiveCdpPort
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
    $profileChromeIds = @(
        Get-CimInstance Win32_Process -Filter "Name = 'chrome.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like "*$profileDirectory*" } |
            Select-Object -ExpandProperty ProcessId
    )
    if ($profileChromeIds.Count -gt 0) {
        Stop-Process -Id $profileChromeIds -Force -ErrorAction SilentlyContinue
    }
    elseif ($null -ne $chromeProcess) {
        Stop-Process -Id $chromeProcess.Id -Force -ErrorAction SilentlyContinue
    }
    $absoluteTargetDirectory = [IO.Path]::GetFullPath($targetDirectory).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar
    $absoluteProfileDirectory = [IO.Path]::GetFullPath($profileDirectory)
    if ($absoluteProfileDirectory.StartsWith(
        $absoluteTargetDirectory,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        Remove-Item -LiteralPath $absoluteProfileDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}
