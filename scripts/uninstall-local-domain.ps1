param([switch]$Elevated)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$isAdministrator = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdministrator -and -not $Elevated) {
    $elevatedProcess = Start-Process `
        -FilePath 'powershell.exe' `
        -ArgumentList @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', ('"{0}"' -f $PSCommandPath),
            '-Elevated'
        ) `
        -Verb RunAs `
        -Wait `
        -PassThru
    if ($elevatedProcess.ExitCode -ne 0) {
        throw "ローカルドメイン解除の管理者処理に失敗しました。終了コード: $($elevatedProcess.ExitCode)"
    }
    return
}

$domain = 'ny.tweetdeck.com'
$hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
$dataRoot = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)) 'NyTweetDeck'
$configPath = Join-Path $dataRoot 'local-domain.json'
if (Test-Path -LiteralPath $configPath) {
    $config = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
    if (-not [string]::IsNullOrWhiteSpace([string]$config.thumbprint)) {
        Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($config.thumbprint)" -Force -ErrorAction SilentlyContinue
        & certutil -user -delstore Root ([string]$config.thumbprint) *> $null
    }
    if (Test-Path -LiteralPath $config.keyStorePath) {
        Remove-Item -LiteralPath $config.keyStorePath -Force
    }
    $certificatePath = Join-Path (Split-Path -Parent $config.keyStorePath) 'ny.tweetdeck.com.cer'
    if (Test-Path -LiteralPath $certificatePath) {
        Remove-Item -LiteralPath $certificatePath -Force
    }
    Remove-Item -LiteralPath $configPath -Force
}
$beginMarker = '# BEGIN NyTweetDeck local domain'
$endMarker = '# END NyTweetDeck local domain'
$hostsContent = Get-Content -Raw -LiteralPath $hostsPath
$managedPattern = '(?ms)^' + [regex]::Escape($beginMarker) + '.*?^' + [regex]::Escape($endMarker) + '\r?\n?'
$updatedHosts = [regex]::Replace($hostsContent, $managedPattern, '')
Set-Content -LiteralPath $hostsPath -Value $updatedHosts -Encoding ascii
Write-Host "ローカルHTTPSを解除しました: https://$domain"
