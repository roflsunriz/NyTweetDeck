param(
    [string]$JarPath = "",
    [switch]$NoBrowser,
    [switch]$ExitAfterReady
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptRoot 'windows-runtime.ps1')

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $scriptRoot 'NyTweetDeck.jar'
}
$resolvedJarPath = if (Test-Path -LiteralPath $JarPath) {
    (Resolve-Path -LiteralPath $JarPath).Path
} else {
    [IO.Path]::GetFullPath($JarPath)
}
$javaProcess = $null
$httpsEnabled = $false

try {
    if (-not (Test-Path -LiteralPath $resolvedJarPath)) {
        throw "NyTweetDeck.jarが見つかりません: $resolvedJarPath"
    }
    $javaCommands = @(Get-Command java -CommandType Application -ErrorAction SilentlyContinue)
    if ($javaCommands.Count -eq 0) {
        throw 'Java 17、21、25のいずれかをインストールしてから再実行してください。'
    }
    $javaCommand = $javaCommands[0]
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
    $javaVersionLine = ($javaVersionOutput -split "`r?`n")[0]
    if ($versionProbe.ExitCode -ne 0 -or $javaVersionLine -notmatch 'version "(?:1\.)?([0-9]+)') {
        throw 'Javaのバージョンを確認できませんでした。'
    }
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 17) {
        throw "Java 17以上が必要です。現在のメジャーバージョン: $javaMajor"
    }

    $domainConfig = Get-NyTweetDeckDomainConfig
    if ($null -ne $domainConfig) {
        Repair-NyTweetDeckRootCertificate -Config $domainConfig
        $httpsEnabled = $true
    }
    Write-NyTweetDeckRuntimeState `
        -Status starting `
        -JarPath $resolvedJarPath `
        -HttpsEnabled $httpsEnabled

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $javaCommand.Source
    $startInfo.Arguments = '-jar "' + $resolvedJarPath.Replace('"', '\"') + '"'
    $startInfo.WorkingDirectory = Split-Path -Parent $resolvedJarPath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = [bool]$NoBrowser
    if ($httpsEnabled) {
        $startInfo.EnvironmentVariables['SERVER_PORT'] = '443'
        $startInfo.EnvironmentVariables['SERVER_SSL_ENABLED'] = 'true'
        $startInfo.EnvironmentVariables['SERVER_SSL_KEY_STORE'] = [string]$domainConfig.keyStorePath
        $startInfo.EnvironmentVariables['SERVER_SSL_KEY_STORE_PASSWORD'] = `
            [string]$domainConfig.keyStorePassword
        $startInfo.EnvironmentVariables['SERVER_SSL_KEY_STORE_TYPE'] = 'PKCS12'
        $startInfo.EnvironmentVariables['NYTWEETDECK_HTTP_PORT'] = '18080'
    }
    $javaProcess = [System.Diagnostics.Process]::Start($startInfo)
    if ($null -eq $javaProcess) {
        throw 'NyTweetDeckのJavaプロセスを開始できませんでした。'
    }
    Write-NyTweetDeckRuntimeState `
        -Status starting `
        -JarPath $resolvedJarPath `
        -HttpsEnabled $httpsEnabled `
        -ProcessId $javaProcess.Id

    Wait-NyTweetDeckReady -RequireHttps $httpsEnabled -TimeoutSeconds 60
    if ($javaProcess.HasExited) {
        throw "NyTweetDeckが準備完了直前に終了しました。終了コード: $($javaProcess.ExitCode)"
    }
    Write-NyTweetDeckRuntimeState `
        -Status ready `
        -JarPath $resolvedJarPath `
        -HttpsEnabled $httpsEnabled `
        -ProcessId $javaProcess.Id

    $accessUrl = if ($httpsEnabled) {
        'https://ny.tweetdeck.com'
    } else {
        'http://127.0.0.1:18080'
    }
    if (-not $NoBrowser) {
        Start-Process $accessUrl
    }
    if ($ExitAfterReady) {
        return
    }
    $javaProcess.WaitForExit()
    if ($javaProcess.ExitCode -ne 0) {
        throw "NyTweetDeckが異常終了しました。終了コード: $($javaProcess.ExitCode)"
    }
}
catch {
    $message = $_.Exception.Message
    Write-NyTweetDeckRuntimeState `
        -Status error `
        -JarPath $resolvedJarPath `
        -HttpsEnabled $httpsEnabled `
        -ProcessId $(if ($null -eq $javaProcess) { 0 } else { $javaProcess.Id }) `
        -Message $message
    throw
}
finally {
    if ($null -ne $javaProcess -and $ExitAfterReady -and -not $javaProcess.HasExited) {
        $javaProcess.Kill()
        $javaProcess.WaitForExit()
    }
    if ($null -ne $javaProcess) {
        $javaProcess.Dispose()
    }
}
