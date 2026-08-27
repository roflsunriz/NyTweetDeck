param([switch]$StopRunning)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptRoot 'windows-runtime.ps1')

if ($StopRunning) {
    $registeredJar = Get-NyTweetDeckTaskJarPath
    Stop-NyTweetDeckRunningInstance -ExpectedJarPaths @($registeredJar)
}
if (Get-ScheduledTask -TaskName 'NyTweetDeck' -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName 'NyTweetDeck' -Confirm:$false
    Write-Host 'NyTweetDeckのログオン自動起動を解除しました。'
} else {
    Write-Host 'NyTweetDeckのログオン自動起動は登録されていません。'
}
