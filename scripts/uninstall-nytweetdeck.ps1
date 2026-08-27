param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptRoot 'windows-runtime.ps1')

$dataRoot = Get-NyTweetDeckDataRoot
$appRoot = [IO.Path]::GetFullPath((Join-Path $dataRoot 'app'))
$registeredJar = Get-NyTweetDeckTaskJarPath
$installedJar = Join-Path $appRoot 'NyTweetDeck.jar'
Stop-NyTweetDeckRunningInstance -ExpectedJarPaths @($registeredJar, $installedJar)

& (Join-Path $scriptRoot 'uninstall-autostart.ps1')
& (Join-Path $scriptRoot 'uninstall-local-domain.ps1')

if (Test-Path -LiteralPath $appRoot) {
    $resolvedDataRoot = [IO.Path]::GetFullPath($dataRoot)
    if (-not $appRoot.StartsWith(
            $resolvedDataRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "想定外のアプリ領域です: $appRoot"
    }
    Remove-Item -LiteralPath $appRoot -Recurse -Force
}
Write-Host 'NyTweetDeck本体、自動起動、ローカルHTTPS設定を解除しました。'
