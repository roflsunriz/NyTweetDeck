param([string]$JarPath = "")

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $version = (& mvn -q help:evaluate '-Dexpression=project.version' '-DforceStdout').Trim()
    $JarPath = Join-Path $repositoryRoot "target\nytweetdeck-$version.jar"
}
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$launcher = Join-Path $repositoryRoot 'scripts\run-nytweetdeck.ps1'
$windowsDefinition = & (Join-Path $repositoryRoot 'scripts\install-autostart.ps1') `
    -JarPath $resolvedJar `
    -LauncherPath $launcher `
    -UserId 'TEST\NyTweetDeck' `
    -StartNow `
    -DryRun | ConvertFrom-Json
if ($windowsDefinition.taskName -ne 'NyTweetDeck' `
        -or $windowsDefinition.trigger -ne 'AtLogOn' `
        -or $windowsDefinition.arguments -notlike '*-NoBrowser*' `
        -or $windowsDefinition.arguments -notlike "*$resolvedJar*" `
        -or -not $windowsDefinition.startNow `
        -or -not $windowsDefinition.verifiesReadiness) {
    throw 'Windowsログオンタスク定義が不正です。'
}

$shellCommands = @(Get-Command sh -CommandType Application -ErrorAction SilentlyContinue)
if ($shellCommands.Count -eq 0) {
    $shellCommands = @(
        Get-Command bash -CommandType Application -ErrorAction SilentlyContinue
    )
}
if ($shellCommands.Count -eq 0) {
    throw 'macOS/Linux自動起動スクリプトの検証にbashが必要です。'
}
$shellPath = $shellCommands[0].Source
$installShell = Join-Path $repositoryRoot 'scripts\install-autostart.sh'
$uninstallShell = Join-Path $repositoryRoot 'scripts\uninstall-autostart.sh'
& $shellPath -n $installShell
if ($LASTEXITCODE -ne 0) { throw 'install-autostart.shの構文検証に失敗しました。' }
& $shellPath -n $uninstallShell
if ($LASTEXITCODE -ne 0) { throw 'uninstall-autostart.shの構文検証に失敗しました。' }
$nativeWindows = $PSVersionTable.PSEdition -ne 'Core' -or $IsWindows
if (-not $nativeWindows) {
    $previousJarPath = $env:NYTWEETDECK_JAR_PATH
    try {
        $env:NYTWEETDECK_JAR_PATH = $resolvedJar
        $linuxDefinition = (& $shellPath $installShell --platform linux --dry-run) -join "`n"
        $macDefinition = (& $shellPath $installShell --platform macos --dry-run) -join "`n"
    }
    finally {
        $env:NYTWEETDECK_JAR_PATH = $previousJarPath
    }
}
else {
    $source = Get-Content -Raw -LiteralPath $installShell
    $linuxDefinition = $source
    $macDefinition = $source
}
if ($linuxDefinition -notlike '*systemd/user/nytweetdeck.service*' `
        -or $linuxDefinition -notlike '*ExecStart=/bin/sh*' `
        -or $linuxDefinition -notlike '*NYTWEETDECK_NO_BROWSER=1*') {
    throw 'Linux systemdユーザーunit定義が不正です。'
}
if ($macDefinition -notlike '*dev.nytweetdeck.plist*' `
        -or $macDefinition -notlike '*<key>RunAtLoad</key><true/>*' `
        -or $macDefinition -notlike '*NYTWEETDECK_NO_BROWSER*') {
    throw 'macOS LaunchAgent定義が不正です。'
}
$autostartSource = Get-Content -Raw -LiteralPath $installShell
if ($autostartSource -notlike '*systemctl --user restart*' `
        -or $autostartSource -notlike '*https://ny.tweetdeck.com/api/v1/system/status*') {
    throw 'macOS/Linux自動起動の再起動・HTTPS確認経路が不正です。'
}
Write-Host 'Windows Task Scheduler、macOS LaunchAgent、Linux systemd userの自動起動定義を検証しました。'
