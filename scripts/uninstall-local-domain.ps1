param([switch]$ElevatedHosts)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$domain = 'ny.tweetdeck.com'
$beginMarker = '# BEGIN NyTweetDeck local domain'
$endMarker = '# END NyTweetDeck local domain'
$hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'

function Remove-ManagedHostsEntry {
    param([Parameter(Mandatory)][string]$HostsPath)

    $hostsContent = Get-Content -Raw -LiteralPath $HostsPath
    $managedPattern = '(?ms)^' + [regex]::Escape($beginMarker) + '.*?^' +
        [regex]::Escape($endMarker) + '\r?\n?'
    $updatedHosts = [regex]::Replace($hostsContent, $managedPattern, '')
    Set-Content -LiteralPath $HostsPath -Value $updatedHosts -Encoding ascii
}

function Remove-CurrentUserRootCertificate {
    param([Parameter(Mandatory)][string]$Thumbprint)

    if (-not (Test-Path -LiteralPath "Cert:\CurrentUser\Root\$Thumbprint")) {
        return
    }
    & certutil.exe -user -delstore Root $Thumbprint *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "現在のユーザーの信頼済みルートから証明書を削除できませんでした: $Thumbprint"
    }
}

if ($ElevatedHosts) {
    $isAdministrator = ([Security.Principal.WindowsPrincipal] `
            [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdministrator) {
        throw 'hostsファイルの更新には管理者権限が必要です。'
    }
    Remove-ManagedHostsEntry -HostsPath $hostsPath
    return
}

$isAdministrator = ([Security.Principal.WindowsPrincipal] `
        [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdministrator) {
    Remove-ManagedHostsEntry -HostsPath $hostsPath
} else {
    $elevatedProcess = Start-Process `
        -FilePath 'powershell.exe' `
        -ArgumentList @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', ('"{0}"' -f $PSCommandPath),
            '-ElevatedHosts'
        ) `
        -Verb RunAs `
        -WindowStyle Hidden `
        -Wait `
        -PassThru
    if ($elevatedProcess.ExitCode -ne 0) {
        throw 'hostsファイルを更新できませんでした。管理者確認を承認して再実行してください。'
    }
}

$dataRoot = Join-Path `
    ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) `
    'NyTweetDeck'
$httpsRoot = Join-Path $dataRoot 'https'
$configPath = Join-Path $dataRoot 'local-domain.json'
if (Test-Path -LiteralPath $configPath) {
    $config = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
    $thumbprints = @()
    if ($null -ne $config.PSObject.Properties['thumbprint']) {
        $thumbprints += [string]$config.thumbprint
    }
    if ($null -ne $config.PSObject.Properties['rootThumbprint']) {
        $thumbprints += [string]$config.rootThumbprint
    }
    foreach ($thumbprint in $thumbprints | Select-Object -Unique) {
        if (-not [string]::IsNullOrWhiteSpace($thumbprint)) {
            Remove-Item `
                -LiteralPath "Cert:\CurrentUser\My\$thumbprint" `
                -Force `
                -ErrorAction SilentlyContinue
            Remove-CurrentUserRootCertificate -Thumbprint $thumbprint
        }
    }
    Remove-Item -LiteralPath $configPath -Force
}

foreach ($fileName in @(
        'ny.tweetdeck.com.p12',
        'ny.tweetdeck.com.cer',
        'nytweetdeck-local-ca.cer',
        'keystore-password',
        'java-capability-before'
    )) {
    $path = Join-Path $httpsRoot $fileName
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Force
    }
}
if (Test-Path -LiteralPath $httpsRoot) {
    $remainingFiles = Get-ChildItem -LiteralPath $httpsRoot -Force
    if ($remainingFiles.Count -eq 0) {
        Remove-Item -LiteralPath $httpsRoot -Force
    }
}

Write-Host "ローカルHTTPSを解除しました: https://$domain"
