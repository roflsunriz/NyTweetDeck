param(
    [ValidateRange(1024, 65535)]
    [int]$BackendPort = 18080,
    [ValidateRange(1024, 65535)]
    [int]$FrontendPort = 5173,
    [switch]$NoBrowser,
    [switch]$ExitAfterReady
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = Split-Path -Parent $scriptRoot
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$logRoot = Join-Path $repositoryRoot 'target'
$backendLogPath = Join-Path $logRoot 'dev-backend.log'
$backendErrorLogPath = Join-Path $logRoot 'dev-backend-error.log'
$frontendLogPath = Join-Path $logRoot 'dev-frontend.log'
$frontendErrorLogPath = Join-Path $logRoot 'dev-frontend-error.log'
$backendProcess = $null
$frontendProcess = $null

function Get-RequiredCommand {
    param([Parameter(Mandatory)][string]$Name)

    $commands = @(Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue)
    if ($commands.Count -eq 0) {
        throw "開発起動に必要なコマンドが見つかりません: $Name"
    }
    return $commands[0]
}

function Test-LocalPortOpen {
    param([Parameter(Mandatory)][int]$Port)

    $client = [Net.Sockets.TcpClient]::new()
    try {
        $connection = $client.ConnectAsync('127.0.0.1', $Port)
        return $connection.Wait(400) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-DevelopmentUrl {
    param(
        [Parameter(Mandatory)][string]$Url,
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory)][string]$Name,
        [int]$TimeoutSeconds = 60
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "$Name が準備完了前に終了しました。終了コード: $($Process.ExitCode)"
        }
        try {
            $request = [Net.WebRequest]::Create($Url)
            $request.Timeout = 1000
            $response = $request.GetResponse()
            $statusCode = [int]$response.StatusCode
            $response.Dispose()
            if ($statusCode -ge 200 -and $statusCode -lt 500) {
                return
            }
        } catch {
            # 起動中の接続拒否は期限まで再試行し、最終的にURL付きエラーへまとめます。
        }
        Start-Sleep -Milliseconds 250
    }
    throw "$Name が${TimeoutSeconds}秒以内に準備完了しませんでした: $Url"
}

function Stop-DevelopmentProcess {
    param([System.Diagnostics.Process]$Process)

    if ($null -eq $Process -or $Process.HasExited) {
        return
    }
    & (Join-Path $env:WINDIR 'System32\taskkill.exe') /PID $Process.Id /T /F 2>$null | Out-Null
}

if ($BackendPort -eq $FrontendPort) {
    throw 'バックエンドとフロントエンドには異なるポートを指定してください。'
}
if (Test-LocalPortOpen -Port $BackendPort) {
    throw "バックエンド用ポートが既に使用されています: 127.0.0.1:$BackendPort"
}
if (Test-LocalPortOpen -Port $FrontendPort) {
    throw "フロントエンド用ポートが既に使用されています: 127.0.0.1:$FrontendPort"
}

$javaCommand = Get-RequiredCommand -Name 'java'
$mavenCommand = Get-RequiredCommand -Name 'mvn'
$bunCommand = Get-RequiredCommand -Name 'bun'
$versionProbeInfo = [System.Diagnostics.ProcessStartInfo]::new()
$versionProbeInfo.FileName = $javaCommand.Source
$versionProbeInfo.Arguments = '-version'
$versionProbeInfo.UseShellExecute = $false
$versionProbeInfo.RedirectStandardError = $true
$versionProbeInfo.RedirectStandardOutput = $true
$versionProbeInfo.CreateNoWindow = $true
$versionProbe = [System.Diagnostics.Process]::Start($versionProbeInfo)
$javaVersionOutput = $versionProbe.StandardError.ReadToEnd() +
    $versionProbe.StandardOutput.ReadToEnd()
$versionProbe.WaitForExit()
$javaVersion = ($javaVersionOutput -split "`r?`n")[0]
$versionExitCode = $versionProbe.ExitCode
$versionProbe.Dispose()
if ($versionExitCode -ne 0 -or $javaVersion -notmatch 'version "(?:1\.)?([0-9]+)') {
    throw 'Javaのバージョンを確認できませんでした。'
}
if ([int]$Matches[1] -lt 17) {
    throw "Java 17以上が必要です。現在のメジャーバージョン: $($Matches[1])"
}
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

try {
    Write-Host "NyTweetDeck backendを起動します: http://127.0.0.1:$BackendPort"
    Write-Host "Backend log: $backendLogPath"
    $backendProcess = Start-Process `
        -FilePath $mavenCommand.Source `
        -ArgumentList @(
            'spring-boot:run',
            '-Dexec.skip=true',
            "-Dspring-boot.run.arguments=--server.port=$BackendPort"
        ) `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $backendLogPath `
        -RedirectStandardError $backendErrorLogPath `
        -PassThru
    Wait-DevelopmentUrl `
        -Url "http://127.0.0.1:$BackendPort/api/v1/system/status" `
        -Process $backendProcess `
        -Name 'NyTweetDeck backend'

    Write-Host "NyTweetDeck frontendを起動します: http://127.0.0.1:$FrontendPort"
    Write-Host "Frontend log: $frontendLogPath"
    $previousBackendOrigin = $env:NYTWEETDECK_BACKEND_ORIGIN
    $previousFrontendPort = $env:NYTWEETDECK_FRONTEND_PORT
    try {
        $env:NYTWEETDECK_BACKEND_ORIGIN = "http://127.0.0.1:$BackendPort"
        $env:NYTWEETDECK_FRONTEND_PORT = [string]$FrontendPort
        $frontendProcess = Start-Process `
            -FilePath $bunCommand.Source `
            -ArgumentList @('run', 'dev') `
            -WorkingDirectory $frontendRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $frontendLogPath `
            -RedirectStandardError $frontendErrorLogPath `
            -PassThru
    } finally {
        $env:NYTWEETDECK_BACKEND_ORIGIN = $previousBackendOrigin
        $env:NYTWEETDECK_FRONTEND_PORT = $previousFrontendPort
    }
    Wait-DevelopmentUrl `
        -Url "http://127.0.0.1:$FrontendPort/" `
        -Process $frontendProcess `
        -Name 'NyTweetDeck frontend'

    $accessUrl = "http://127.0.0.1:$FrontendPort/"
    Write-Host "NyTweetDeck開発環境の準備が完了しました: $accessUrl"
    Write-Host '終了するには Ctrl+C を押してください。'
    if (-not $NoBrowser) {
        Start-Process $accessUrl
    }
    if ($ExitAfterReady) {
        return
    }

    while (-not $backendProcess.HasExited -and -not $frontendProcess.HasExited) {
        Start-Sleep -Milliseconds 500
    }
    if ($backendProcess.HasExited) {
        throw "NyTweetDeck backendが終了しました。終了コード: $($backendProcess.ExitCode)"
    }
    throw "NyTweetDeck frontendが終了しました。終了コード: $($frontendProcess.ExitCode)"
} finally {
    Stop-DevelopmentProcess -Process $frontendProcess
    Stop-DevelopmentProcess -Process $backendProcess
    if ($null -ne $frontendProcess) {
        $frontendProcess.Dispose()
    }
    if ($null -ne $backendProcess) {
        $backendProcess.Dispose()
    }
}
