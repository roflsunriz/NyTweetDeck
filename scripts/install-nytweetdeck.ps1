param(
    [string]$JarPath = "",
    [string]$SourceScriptDirectory = "",
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$installerRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($SourceScriptDirectory)) {
    $SourceScriptDirectory = $installerRoot
}
$sourceScriptRoot = (Resolve-Path -LiteralPath $SourceScriptDirectory).Path
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $installerRoot 'NyTweetDeck.jar'
}
$sourceJar = (Resolve-Path -LiteralPath $JarPath).Path
$sourceLauncher = Join-Path $sourceScriptRoot 'run-nytweetdeck.ps1'
$sourceRuntimeHelper = Join-Path $sourceScriptRoot 'windows-runtime.ps1'
$sourceDomainInstaller = Join-Path $sourceScriptRoot 'install-local-domain.ps1'
$sourceAutostartInstaller = Join-Path $sourceScriptRoot 'install-autostart.ps1'
$sourceIntegratedUninstaller = Join-Path $sourceScriptRoot 'uninstall-nytweetdeck.ps1'
$sourceAutostartUninstaller = Join-Path $sourceScriptRoot 'uninstall-autostart.ps1'
$sourceDomainUninstaller = Join-Path $sourceScriptRoot 'uninstall-local-domain.ps1'
foreach ($requiredPath in @(
        $sourceJar,
        $sourceLauncher,
        $sourceRuntimeHelper,
        $sourceDomainInstaller,
        $sourceAutostartInstaller,
        $sourceIntegratedUninstaller,
        $sourceAutostartUninstaller,
        $sourceDomainUninstaller
    )) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "統合インストールに必要なファイルがありません: $requiredPath"
    }
}
. $sourceRuntimeHelper

$dataRoot = Join-Path `
    ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) `
    'NyTweetDeck'
$appRoot = Join-Path $dataRoot 'app'
$installedJar = Join-Path $appRoot 'NyTweetDeck.jar'
$installedLauncher = Join-Path $appRoot 'run-nytweetdeck.ps1'
$installedRuntimeHelper = Join-Path $appRoot 'windows-runtime.ps1'
$installedIntegratedUninstaller = Join-Path $appRoot 'uninstall-nytweetdeck.ps1'
$installedAutostartUninstaller = Join-Path $appRoot 'uninstall-autostart.ps1'
$installedDomainUninstaller = Join-Path $appRoot 'uninstall-local-domain.ps1'
$plan = [ordered]@{
    schemaVersion = 1
    sourceJar = $sourceJar
    appRoot = $appRoot
    installedJar = $installedJar
    installedLauncher = $installedLauncher
    installedUninstaller = $installedIntegratedUninstaller
    installsLocalHttps = $true
    registersAutostart = $true
    restartsAndVerifies = $true
}
if ($DryRun) {
    $plan | ConvertTo-Json -Depth 3
    return
}

New-Item -ItemType Directory -Path $appRoot -Force | Out-Null
$stagingRoot = Join-Path $appRoot ('.install-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $stagingRoot | Out-Null
$installFiles = @(
    [pscustomobject]@{
        Source = $sourceJar
        Staged = Join-Path $stagingRoot 'NyTweetDeck.jar'
        Destination = $installedJar
    },
    [pscustomobject]@{
        Source = $sourceLauncher
        Staged = Join-Path $stagingRoot 'run-nytweetdeck.ps1'
        Destination = $installedLauncher
    },
    [pscustomobject]@{
        Source = $sourceRuntimeHelper
        Staged = Join-Path $stagingRoot 'windows-runtime.ps1'
        Destination = $installedRuntimeHelper
    },
    [pscustomobject]@{
        Source = $sourceIntegratedUninstaller
        Staged = Join-Path $stagingRoot 'uninstall-nytweetdeck.ps1'
        Destination = $installedIntegratedUninstaller
    },
    [pscustomobject]@{
        Source = $sourceAutostartUninstaller
        Staged = Join-Path $stagingRoot 'uninstall-autostart.ps1'
        Destination = $installedAutostartUninstaller
    },
    [pscustomobject]@{
        Source = $sourceDomainUninstaller
        Staged = Join-Path $stagingRoot 'uninstall-local-domain.ps1'
        Destination = $installedDomainUninstaller
    }
)
foreach ($file in $installFiles) {
    $file | Add-Member -NotePropertyName HadDestination -NotePropertyValue $false
    $file | Add-Member -NotePropertyName Changed -NotePropertyValue $false
}
$taskExisted = $null -ne (Get-ScheduledTask -TaskName 'NyTweetDeck' -ErrorAction SilentlyContinue)
$previousTaskJar = if ($taskExisted) { Get-NyTweetDeckTaskJarPath } else { $null }
$previousTaskLauncher = if ($taskExisted) { Get-NyTweetDeckTaskLauncherPath } else { $null }
$installed = $false
try {
    Stop-NyTweetDeckRunningInstance `
        -ExpectedJarPaths @($previousTaskJar, $sourceJar, $installedJar)
    foreach ($file in $installFiles) {
        Copy-Item -LiteralPath $file.Source -Destination $file.Staged -Force
        $backupPath = "$($file.Destination).bak"
        $file.HadDestination = Test-Path -LiteralPath $file.Destination
        if ($file.HadDestination) {
            if (Test-Path -LiteralPath $backupPath) {
                Remove-Item -LiteralPath $backupPath -Force
            }
            [IO.File]::Replace($file.Staged, $file.Destination, $backupPath, $true)
        }
        else {
            if (Test-Path -LiteralPath $backupPath) {
                Remove-Item -LiteralPath $backupPath -Force
            }
            Move-Item -LiteralPath $file.Staged -Destination $file.Destination
        }
        $file.Changed = $true
    }

    $requiresHttpsInstallation = $false
    try {
        $existingDomainConfig = Get-NyTweetDeckDomainConfig
        if ($null -eq $existingDomainConfig) {
            $requiresHttpsInstallation = $true
        }
        else {
            Repair-NyTweetDeckRootCertificate -Config $existingDomainConfig
            if (-not (Test-NyTweetDeckHostsMapping)) {
                $requiresHttpsInstallation = $true
            }
        }
    }
    catch {
        $requiresHttpsInstallation = $true
    }
    if ($requiresHttpsInstallation) {
        & $sourceDomainInstaller -SkipRegisteredTaskRestart
    }
    & $sourceAutostartInstaller `
        -JarPath $installedJar `
        -LauncherPath $installedLauncher `
        -StartNow
    Wait-NyTweetDeckReady -RequireHttps $true -TimeoutSeconds 60
    foreach ($file in $installFiles) {
        $backupPath = "$($file.Destination).bak"
        if (Test-Path -LiteralPath $backupPath) {
            Remove-Item -LiteralPath $backupPath -Force
        }
    }
    $installed = $true
}
finally {
    if (-not $installed) {
        foreach ($file in $installFiles) {
            if (-not $file.Changed) {
                continue
            }
            $backupPath = "$($file.Destination).bak"
            if (Test-Path -LiteralPath $backupPath) {
                Copy-Item -LiteralPath $backupPath -Destination $file.Destination -Force
                Remove-Item -LiteralPath $backupPath -Force
            }
            elseif (-not $file.HadDestination -and `
                    (Test-Path -LiteralPath $file.Destination)) {
                Remove-Item -LiteralPath $file.Destination -Force
            }
        }
        if (-not $taskExisted -and `
                (Get-ScheduledTask -TaskName 'NyTweetDeck' -ErrorAction SilentlyContinue)) {
            Unregister-ScheduledTask -TaskName 'NyTweetDeck' -Confirm:$false
        }
        elseif ($taskExisted `
                -and -not [string]::IsNullOrWhiteSpace($previousTaskJar) `
                -and -not [string]::IsNullOrWhiteSpace($previousTaskLauncher) `
                -and (Test-Path -LiteralPath $previousTaskJar) `
                -and (Test-Path -LiteralPath $previousTaskLauncher)) {
            try {
                & $sourceAutostartInstaller `
                    -JarPath $previousTaskJar `
                    -LauncherPath $previousTaskLauncher `
                    -StartNow
            }
            catch {
                Write-Warning '更新前のNyTweetDeck自動起動タスクを復旧できませんでした。'
            }
        }
    }
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}

Write-Host 'NyTweetDeckを安定したアプリ領域へインストールし、HTTPSと自動起動を確認しました。'
Write-Host 'アクセス先: https://ny.tweetdeck.com'
