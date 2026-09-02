# NyTweetDeck Android

## 配布APKのインストールと更新

GitHub Releasesの`android-v`で始まる最新版から、`NyTweetDeck-Android-v*.apk`と同名の`.sha256`をダウンロードする。Android 8.0以降でAPKを開き、初回だけダウンロードに使用したブラウザへ「不明なアプリのインストール」を許可する。

更新は同じ署名の新しいAPKを上書きする。ログイン状態と設定を維持するため、署名不一致時にアンインストールして続行しない。

## Issue #1 の追加設定

設定画面上部の「フィード更新」から自動更新を停止・再開できます。表示言語とは独立した投稿の翻訳先言語、動画の既定画質（自動／低／中／高）、メインナビゲーションのサイド／下部配置も選べます。複数画質の動画は再生中の画質メニューでも切り替えられます。各タイムラインカラムの上部では、Xの検索ランキングを使う「トップ」と「最新」を選べます。

X Web API定義の更新には、端末のChrome/WebViewバージョンを保ちながらWebView専用識別子を除いたブラウザ互換User-Agentを使用します。更新に一時失敗しても同梱済みの検証済み定義を維持し、設定画面から再試行できます。

「メインナビゲーションを常時表示」をオフにするとメニューを自動収納できます。収納中はサイド配置なら画面端から内側、下部配置なら下端から上へスワイプするかメニューボタンで一時表示でき、3秒後に再び収納します。オンへ戻すと常時表示になります。

画像と動画は2本指のピンチ操作に対応します。動画は等倍から最大4倍まで拡大でき、ピンチインで等倍まで縮小し、拡大中はパンできます。ダブルタップすると等倍・中央位置へ戻ります。

返信ポストを詳細で開いた場合は、取得できた親返信を対象ポストの前に表示し、返信一覧を下へスクロールすると過去の返信を追加取得します。

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
使用する。タイムライン、詳細/翻訳/メディア/Live状態、対象カラム、
通知/トレンド/DM、pull更新、コールド時キャッシュを読み取り検証した後、
アプリデータを消さずrelease APKへ戻す。
失敗時も自動アンインストールは行わない。

実動画は外部タイムラインに動画候補が存在する時だけ成立するため、既定セットとは分離して次を実行する。取得した候補から実再生を確認し、同じ動画を開き直して共有ディスクキャッシュが維持されることを検証する。

```powershell
.\run-aquos-live-tests.ps1 -TestClasses dev.nytweetdeck.android.LiveVideoPlaybackSmokeTest
```

X上の状態を一時変更する可逆mutationテストは既定セットに含めない。所有者が
明示的に許可した場合だけ、次のように単独指定する。いいね・リポスト・履歴保存は
元状態へ戻し、検証用ポスト・返信・引用は取得したIDを使って削除する。

```powershell
.\run-aquos-live-tests.ps1 -AdbPath C:\path\to\adb.exe -MutationAuthorized -TestClasses dev.nytweetdeck.android.LiveReversibleMutationSmokeTest
```
