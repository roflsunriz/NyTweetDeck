param(
    [string]$AdbPath = "adb",
    [string]$ApkPath = "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk"
)

$resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop

# 本体APKは認証・レイアウトを保持するため、testOnly用の -t や自動uninstallを使わない。
& $AdbPath install -r $resolvedApk.Path
if ($LASTEXITCODE -ne 0) {
    throw "NyTweetDeck Android releaseの上書きインストールに失敗しました。"
}
