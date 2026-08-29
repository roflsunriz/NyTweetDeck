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
