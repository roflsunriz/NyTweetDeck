param(
    [string]$JarPath = "",
    [string]$LauncherPath = "",
    [string]$UserId = "",
    [switch]$StartNow,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows -and -not $DryRun) {
    throw 'Windows用スクリプトです。macOSまたはLinuxではinstall-autostart.shを使用してください。'
}
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($LauncherPath)) {
    $LauncherPath = Join-Path $scriptRoot 'run-nytweetdeck.ps1'
}
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $scriptRoot 'NyTweetDeck.jar'
}
$resolvedLauncher = [IO.Path]::GetFullPath($LauncherPath)
$resolvedJar = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedLauncher)) {
    throw "ランチャーが見つかりません: $resolvedLauncher"
}
if (-not (Test-Path -LiteralPath $resolvedJar)) {
    throw "NyTweetDeck.jarが見つかりません: $resolvedJar"
}
if ([string]::IsNullOrWhiteSpace($UserId)) {
    try {
        $UserId = (Get-CimInstance Win32_ComputerSystem).UserName
    }
    catch {
        $UserId = $null
    }
    if ([string]::IsNullOrWhiteSpace($UserId)) {
        $UserId = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    }
}
$arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -JarPath "{1}" -NoBrowser' -f $resolvedLauncher, $resolvedJar
$definition = [ordered]@{
    taskName = 'NyTweetDeck'
    userId = $UserId
    executable = 'powershell.exe'
    arguments = $arguments
    workingDirectory = Split-Path -Parent $resolvedJar
    trigger = 'AtLogOn'
}
if ($DryRun) {
    $definition | ConvertTo-Json -Depth 3
    return
}

$action = New-ScheduledTaskAction `
    -Execute $definition.executable `
    -Argument $definition.arguments `
    -WorkingDirectory $definition.workingDirectory
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $UserId
$principal = New-ScheduledTaskPrincipal -UserId $UserId -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask `
    -TaskName $definition.taskName `
    -Action $action `
    -Trigger $trigger `
    -Principal $principal `
    -Settings $settings `
    -Description 'ユーザーログオン時にNyTweetDeckをloopback限定で起動します。' `
    -Force | Out-Null
if ($StartNow) {
    Start-ScheduledTask -TaskName $definition.taskName
}
Write-Host "NyTweetDeckのログオン自動起動を登録しました: user=$UserId"
