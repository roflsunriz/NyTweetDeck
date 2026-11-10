# 更新手順

## 前提

コマンド中の山括弧は、実際の版番号・対象端末のserialへ置き換える。

リポジトリ直下で作業し、`COMMON-AGENTS.md`、`findings.md`、`AGENTS.md`を確認する。`git status --short --branch`で既存差分を確認し、稼働先、起動方法、対象Android端末のserialを特定する。認証情報・設定・証明書・署名鍵を表示・コミットしない。

PC版はJava 17/21/25、Maven、Bun、Google Chrome、Android版はJava 17と`android/local.properties`で指定したSDKを使用する。SDK/Gradle/AGPの指定は`android/app/build.gradle.kts`、`android/gradle/wrapper/gradle-wrapper.properties`、リリースworkflowを正本にする。

## 検証と公開

```powershell
Set-Location frontend
bun install --frozen-lockfile
bun run lint
bun run format:check
bun run type-check
bun run test
bun audit
Set-Location ..
mvn --batch-mode --no-transfer-progress '-Drevision=<PC版の版番号>' verify
.\scripts\audit-maven.ps1
.\scripts\audit-gradle.ps1
.\scripts\verify-ui.ps1 -JarPath .\target\nytweetdeck-<PC版の版番号>.jar
.\scripts\verify-autostart.ps1 .\target\nytweetdeck-<PC版の版番号>.jar
.\scripts\verify-installation.ps1 .\target\nytweetdeck-<PC版の版番号>.jar
.\scripts\verify-local-domain.ps1 .\target\nytweetdeck-<PC版の版番号>.jar
Set-Location android
.\gradlew.bat --no-daemon --no-problems-report testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease
Set-Location ..
```

実機は`adb -s <serial>`を毎回明示する。本体へは`install -r`、テストAPKだけへ`install -r -t`を使う。署名を先に比較し、認証状態を保持する。UIテストは実Xの変更を起こさないfixtureを優先し、失敗しても本体をアンインストールしない。検証後は同署名の非debuggable releaseへ戻し、端末APKのSHA-256を照合する。

`CHANGELOG.md`へPC版とAndroid版を別見出しで記録し、Androidの`versionName`と`versionCode`を更新する。`verify-release-notes.ps1`で両見出しを検証してmainへコミット・プッシュし、同じ検証済みcommitへ`v<PC版>`と`android-v<Android版>`を付ける。既存Release/Android release workflowの終了を待ち、公開ZIP/APK・SHA-256・署名・版を確認する。CI起動だけで成功扱いにしない。

## 稼働版への反映と復旧

PCは公開ZIPを展開し、同梱`install-nytweetdeck.ps1`（macOS/Linuxは`.sh`）で更新する。旧JARをバックアップして対象プロセスだけを停止し、既存の認証情報・設定・HTTPS・自動起動を維持する。HTTP/HTTPSの準備完了、稼働JARの版と公開物のハッシュ、配信画面の修正挙動を確認する。失敗時はバックアップJARと旧起動設定へ戻し、同じ応答を再確認する。

Androidは公開署名APKを指定端末へ`adb -s <serial> install -r <apk>`で上書きし、版、非debuggable、起動、端末APKのハッシュを確認する。署名不一致・接続不能ではデータを消して続行しない。AndroidのダウングレードはOSが拒否する場合があるため、旧APKへの復旧のためにアプリデータを削除しない。

## 依存関係

定義とlockfileを一緒に更新し、公式metadata・互換条件を確認する。Bun/Maven/Gradle監査を実行する。Dependabotの定期確認・自動マージは毎月28日（Asia/Tokyo）の既存workflowに従い、今回の機能修正と無関係なPRを途中でマージしない。
