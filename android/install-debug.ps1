param(
    [string]$AdbPath = "adb",
    [string]$ApkPath = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
)

$resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop

# AQUOS (Android 16) では通常APKへの `adb install -r -t` がアプリデータを
# 消去することを実機で確認済み。-t はandroidTest APKだけに限定する。
& $AdbPath install -r $resolvedApk.Path
if ($LASTEXITCODE -ne 0) {
    throw "NyTweetDeck Androidの上書きインストールに失敗しました。"
}
