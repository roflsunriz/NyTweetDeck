param([switch]$StopRunning)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($StopRunning) {
    Stop-ScheduledTask -TaskName 'NyTweetDeck' -ErrorAction SilentlyContinue
}
if (Get-ScheduledTask -TaskName 'NyTweetDeck' -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName 'NyTweetDeck' -Confirm:$false
    Write-Host 'NyTweetDeckのログオン自動起動を解除しました。'
} else {
    Write-Host 'NyTweetDeckのログオン自動起動は登録されていません。'
}
