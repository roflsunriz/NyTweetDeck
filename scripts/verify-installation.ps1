param([string]$JarPath = "")

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $version = (& mvn -q help:evaluate '-Dexpression=project.version' '-DforceStdout').Trim()
    $JarPath = Join-Path $repositoryRoot "target\nytweetdeck-$version.jar"
}
$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
$powershellScripts = @(
    'windows-runtime.ps1',
    'run-nytweetdeck.ps1',
    'install-local-domain.ps1',
    'uninstall-local-domain.ps1',
    'install-autostart.ps1',
    'uninstall-autostart.ps1',
    'install-nytweetdeck.ps1',
    'uninstall-nytweetdeck.ps1'
)
foreach ($scriptName in $powershellScripts) {
    $tokens = $null
    $parseErrors = $null
    $scriptPath = Join-Path $PSScriptRoot $scriptName
    [Management.Automation.Language.Parser]::ParseFile(
        $scriptPath,
        [ref]$tokens,
        [ref]$parseErrors) | Out-Null
    if ($parseErrors.Count -gt 0) {
        throw "PowerShell構文が不正です: $scriptName - $($parseErrors[0].Message)"
    }
}

$windowsPlan = & (Join-Path $PSScriptRoot 'install-nytweetdeck.ps1') `
    -JarPath $resolvedJar `
    -SourceScriptDirectory $PSScriptRoot `
    -DryRun | ConvertFrom-Json
$normalizedInstalledJar = [string]$windowsPlan.installedJar -replace '\\', '/'
$normalizedInstalledUninstaller = [string]$windowsPlan.installedUninstaller -replace '\\', '/'
if ($windowsPlan.schemaVersion -ne 1 `
        -or $normalizedInstalledJar -notlike '*NyTweetDeck/app/NyTweetDeck.jar' `
        -or $normalizedInstalledUninstaller -notlike '*NyTweetDeck/app/uninstall-nytweetdeck.ps1' `
        -or -not $windowsPlan.installsLocalHttps `
        -or -not $windowsPlan.registersAutostart `
        -or -not $windowsPlan.restartsAndVerifies) {
    throw 'Windows統合インストール計画が不正です。'
}
$windowsInstallerSource = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'install-nytweetdeck.ps1')
if ($windowsInstallerSource -notlike '*Repair-NyTweetDeckRootCertificate*' `
        -or $windowsInstallerSource -notlike '*Test-NyTweetDeckHostsMapping*' `
        -or $windowsInstallerSource -notlike '*File]::Replace*') {
    throw 'Windows統合インストールの既存CA維持または原子的置換経路が不正です。'
}
$autostartPlan = & (Join-Path $PSScriptRoot 'install-autostart.ps1') `
    -JarPath $resolvedJar `
    -LauncherPath (Join-Path $PSScriptRoot 'run-nytweetdeck.ps1') `
    -UserId 'TEST\NyTweetDeck' `
    -StartNow `
    -DryRun | ConvertFrom-Json
if (-not $autostartPlan.startNow `
        -or -not $autostartPlan.verifiesReadiness `
        -or $autostartPlan.arguments -notlike '*-NoBrowser*') {
    throw 'Windows自動起動の再起動・準備確認計画が不正です。'
}
$windowsLauncherSource = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'run-nytweetdeck.ps1')
if ($windowsLauncherSource -like '*--server.ssl.key-store-password*' `
        -or $windowsLauncherSource -notlike '*SERVER_SSL_KEY_STORE_PASSWORD*' `
        -or $windowsLauncherSource -notlike '*Repair-NyTweetDeckRootCertificate*') {
    throw 'Windowsランチャーの秘密情報または専用CA自己修復経路が不正です。'
}

$nativeWindows = $PSVersionTable.PSEdition -ne 'Core' -or $IsWindows
if ($nativeWindows) {
    $gitBash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
    $gitUtilities = Join-Path $env:ProgramFiles 'Git\usr\bin'
    $shellPath = if (Test-Path -LiteralPath $gitBash) {
        $gitBash
    } else {
        $null
    }
} else {
    $shell = Get-Command sh -CommandType Application -ErrorAction SilentlyContinue
    $shellPath = if ($null -eq $shell) { $null } else { $shell.Source }
}
if ([string]::IsNullOrWhiteSpace($shellPath)) {
    throw 'macOS/Linux統合インストール検証にshが必要です。'
}
$shellScripts = @(
    'run-nytweetdeck.sh',
    'install-local-domain.sh',
    'uninstall-local-domain.sh',
    'install-autostart.sh',
    'uninstall-autostart.sh',
    'install-nytweetdeck.sh',
    'uninstall-nytweetdeck.sh'
)
foreach ($scriptName in $shellScripts) {
    & $shellPath -n (Join-Path $PSScriptRoot $scriptName)
    if ($LASTEXITCODE -ne 0) {
        throw "shell構文が不正です: $scriptName"
    }
}
if ($nativeWindows) {
    $installerSource = Get-Content -Raw -LiteralPath (
        Join-Path $PSScriptRoot 'install-nytweetdeck.sh')
    $linuxPlan = $installerSource
    $macPlan = $installerSource
    foreach ($plan in @($linuxPlan, $macPlan)) {
        if ($plan -notlike '*APP_ROOT*' `
                -or $plan -notlike '*INSTALLED_JAR*' `
                -or $plan -notlike '*SOURCE_DOMAIN_INSTALLER*' `
                -or $plan -notlike '*SOURCE_AUTOSTART_INSTALLER*' `
                -or $plan -notlike '*HTTPS_READY*' `
                -or $plan -notlike '*https://ny.tweetdeck.com*') {
            throw 'macOS/Linux統合インストール計画が不正です。'
        }
    }
}
else {
    $linuxPlan = (& $shellPath (Join-Path $PSScriptRoot 'install-nytweetdeck.sh') `
            --jar $resolvedJar --script-dir $PSScriptRoot --platform linux --dry-run) -join "`n"
    $macPlan = (& $shellPath (Join-Path $PSScriptRoot 'install-nytweetdeck.sh') `
            --jar $resolvedJar --script-dir $PSScriptRoot --platform macos --dry-run) -join "`n"
    foreach ($plan in @($linuxPlan, $macPlan)) {
        if ($plan -notlike '*app/NyTweetDeck.jar*' `
                -or $plan -notlike '*app/uninstall-nytweetdeck.sh*' `
                -or $plan -notlike '*installsLocalHttps=true*' `
                -or $plan -notlike '*registersAutostart=true*' `
                -or $plan -notlike '*restartsAndVerifies=true*') {
            throw 'macOS/Linux統合インストール計画が不正です。'
        }
    }
}
$shellLauncherSource = Get-Content -Raw -LiteralPath (
    Join-Path $PSScriptRoot 'run-nytweetdeck.sh')
if ($shellLauncherSource -like '*--server.ssl.key-store-password*' `
        -or $shellLauncherSource -notlike '*SERVER_SSL_KEY_STORE_PASSWORD*' `
        -or $shellLauncherSource -notlike '*openssl verify*' `
        -or $shellLauncherSource -notlike '*security verify-cert*') {
    throw 'macOS/Linuxランチャーの秘密情報または信頼ストア確認経路が不正です。'
}
Write-Host '統合インストール、CA自己修復、安定配置、再起動、準備確認を検証しました。'
