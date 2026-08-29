# NyTweetDeck Android

## データを維持する実機更新

debug本体APKは次で上書きする。

```powershell
.\install-debug.ps1 -AdbPath C:\path\to\adb.exe
```

AQUOS（Android 16）では、通常の本体APKへ `adb install -r -t` を使うと
アプリデータが消去された。`-t` は `app-debug-androidTest.apk` の導入にだけ使い、
本体APKには使用しない。署名が異なる場合は上書きせず、ログイン状態を失う
アンインストールを自動実行しない。

## 署名付きrelease

署名鍵とパスワードはGitへ保存せず、ビルドプロセスの環境変数だけから渡す。

```powershell
$env:NYTD_ANDROID_KEYSTORE = 'C:\secure\nytweetdeck-release.keystore'
$env:NYTD_ANDROID_STORE_PASSWORD = '<store password>'
$env:NYTD_ANDROID_KEY_ALIAS = '<key alias>'
$env:NYTD_ANDROID_KEY_PASSWORD = '<key password>'
.\gradlew.bat clean testDebugUnitTest lintRelease assembleRelease
```

署名環境変数がない通常CIではunsigned releaseを検証し、タグrelease workflowでは
GitHub Secretsから一時keystoreへ復元して署名・検証・SHA-256生成を行う。AQUOSへ
データを保持して更新する場合は次を使う。

```powershell
.\install-release.ps1 -AdbPath C:\path\to\adb.exe
```

## 認証状態を保持するAQUOS実Xテスト

通常の `connectedDebugAndroidTest` は本体を再導入・削除するため、ログイン後の実X
検証には使用しない。debug本体とandroidTest APKを事前ビルドし、release APKと
同じ署名であることを確認してから次を実行する。

```powershell
.\run-aquos-live-tests.ps1 -AdbPath C:\path\to\adb.exe
```

スクリプトは本体debug APKへ `-t` を付けずに上書きし、test APKだけへ `-t` を
使用する。タイムライン、対象カラム、通知/トレンド/DM、pull更新、コールド時
キャッシュを読み取り検証した後、アプリデータを消さずrelease APKへ戻す。
失敗時も自動アンインストールは行わない。
