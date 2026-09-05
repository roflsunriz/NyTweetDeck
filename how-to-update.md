# 更新手順

## 前提

- JDK 17、21、25のいずれか（配布物生成の基準はJDK 17）
- Maven 3.9以上
- Bun 1.4以上
- OpenSSL（WindowsではGit for Windows同梱版を利用可能）
- Android版の検証時はAndroid SDK 36とJDK 17
- 作業前のGit状態が把握できていること

## 依存関係の更新

1. Spring Bootの公式ドキュメントで安定版と対応Javaバージョンを確認します。
2. npm公式レジストリと各公式リポジトリで、更新対象のリリース、保守状況、ライセンスを確認します。
3. `pom.xml`または`frontend/package.json`の固定バージョンを更新します。
4. フロントエンドの変更時は次を実行してlockfileを更新します。

```powershell
Set-Location .\frontend
bun install
```

5. lockfileの差分に、意図しない依存関係や配布元の変更がないことを確認します。

XのWeb資産またはAPI仕様が変わった場合も、queryId・operation metadata・Feature Switchは起動後と6時間ごとにX公式Web資産から自動更新されます。設定画面の「X Web API定義」から手動更新し、最新定義の使用状態と取得元バージョンを確認できます。全必須operationが揃わない場合は更新せず、同梱または直前の検証済み定義を維持します。

更新機構自体を変更した場合は、公式ドメイン制限、資産サイズ上限、queryId形式、必須operation完全性、Feature Boolean抽出、失敗時フォールバックをテストします。公開Bearer Token、Cookie、アカウント情報はログ、生成物、変更履歴へ含めません。

X公式Webの動画、画像、返信、リンク、プロフィール遷移の挙動が変わった場合は、`frontend/scripts/README.sandbox.md`を確認し、raw CDPを公開する専用Chromeで公式資産を再取得します。captureはGit管理外の`frontend/src/sandbox/x-reference-captures/`だけへ保存し、Cookie、Authorization、header、HTML、DOM、スクリーンショット、実ユーザーデータを保存しません。

```powershell
Set-Location .\frontend
bun run sandbox:capture-x-reference
bun run sandbox:analyze-x-reference
bun run sandbox:inventory-x-reference
bun run sandbox:verify-x-offline
bun run sandbox:run-x-url-contract
```

意味契約を更新する前に、新規BrowserContextで外部HTTP・HTTPS・WebSocket・FTPが遮断され、CookieとWeb Storageが空であることを確認します。公式moduleの対象関数は複数captureのde-minify結果と動的挙動から特定し、調査用instrumentationはcapture内だけへ追加します。機能語の出現数だけで挙動を断定せず、合成mockの境界値と操作列で前後状態を観測してから、Xコードを含まない契約テストと独自実装へ反映します。

観測範囲は動画、画像、返信、リンク、プロフィール遷移へ限定しません。`sandbox:inventory-x-reference`で全surface・コンテンツ型・UI要素・状態・入力・環境・データ条件の台帳と、実captureから自動発見したbundle token・role・タグ・ARIA状態を比較します。新しい要素が見つかった場合は既存の対象集合へ追加し、未観測、観測済み、契約化済み、デスクトップ実装済み、Android実装済み、両版検証済みを区別して追跡します。

X公式挙動を取り込む際も、自由追加・削除・並べ替え可能な任意数カラム、左メインメニューの常設とカスタマイズ、複数アカウントのログイン状態保持・自動選択・切替を製品不変条件として維持します。Xの単一フィードまたは単一アカウント前提の状態はカラム単位・選択アカウント単位へ適応し、これらを削除する変更を互換対応として扱いません。

Issue #1のようにデスクトップ版とAndroid版の両方へ機能を追加する場合は、タイムライン取得・設定移行・詳細返信・動画再生の各経路を同じ受入条件で更新します。カラムの「トップ／最新」、タイムライン自動更新、投稿の翻訳先言語、動画画質は両版の設定へ追加し、旧設定を既定値で移行できることを確認します。返信詳細は親返信の文脈と追加ページのcursorを、動画はX応答の複数MP4 variantを、各版の単体テストとUIテストで確認します。

## 検証

```powershell
Set-Location .\frontend
bun run lint
bun run format
bun run type-check
bun run build
bun run test
bun audit
Set-Location ..
mvn verify
.\scripts\audit-maven.ps1
.\scripts\audit-gradle.ps1
```

開発ランチャーを変更した場合は、リリースJARやバージョン付きファイル名を用意せず、別ポートでバックエンドとフロントエンドの準備完了および終了後のポート解放を確認します。

```powershell
.\scripts\run-dev.ps1 -BackendPort 18082 -FrontendPort 5174 -NoBrowser -ExitAfterReady
```

```sh
sh ./scripts/run-dev.sh --backend-port 18083 --frontend-port 5175 --no-browser --exit-after-ready
```

Java互換性を変更した場合は、JDK 17で`--release 17`コンパイルと全テストを実行した後、JDK 21・25でも同じテストとJAR起動を確認します。CIのメーカー行列は`docs/jdk-compatibility.md`の固定一覧と一致させます。

ブラウザUIを変更した場合は、デスクトップ、狭幅、低高さの代表的なビューポートで、カラム追加・削除、設定変更、再読み込み後の復元を確認します。動画表示を変更した場合は、ビューポート上端〜中央への進入前は`src`が未設定で、進入時だけロード・自動再生され、離脱時は停止して`src`も解除されることを確認します。

設定永続化を変更した場合は、共有ストアが空のときだけ旧`localStorage`を移行すること、既存共有設定をアドレス別の旧値で上書きしないこと、設定版1〜7のカラムを保持して現行版へ原子的に移行できること、`127.0.0.1`と`localhost`から同じリビジョンを取得できること、同時更新の競合時に未保存操作を最新リビジョンへ再適用することを確認します。復旧検証では、初回保存時から`.bak`が作成されること、本体の破損時と欠落時に正常な`.bak`を復元すること、バックアップがない場合と両方が破損した場合に既定設定を再生成して再起動後も読めること、バックエンド停止中に読込エラーになった画面が再起動後に自動復帰することを確認します。

```powershell
.\scripts\verify-ui.ps1
```

この検証はパッケージ済みJARを起動し、ヘッドレスChromeでデスクトップ・タブレット・モバイル・RTL設定画面を操作します。CIとリリースワークフローでも同じ検証を実行します。

リリース前はランチャーからJARを起動し、準備完了後にブラウザが開くこと、ZIP内のJARとローカル検証済みJARのSHA-256が一致することを確認します。

自動起動またはローカルドメインのスクリプトを変更した場合は、次も実行します。証明書検証は一時的な専用ルートCA、CA署名済みサーバー証明書、PKCS#12、高位ポートを使い、端末のhostsや信頼ストアを変更せず、ホスト名と証明書チェーンを検証します。

```powershell
.\scripts\verify-autostart.ps1
.\scripts\verify-installation.ps1
.\scripts\verify-local-domain.ps1
```

リリースワークフローは`v`を除いたタグをMavenの`revision`へ渡し、`SNAPSHOT`ではないJARを生成します。タグはSemVer形式で、mainの履歴に含まれるコミットを指す必要があります。
GitHub Releaseの本文には、`CHANGELOG.md`の同じバージョン見出しから次のバージョン見出し直前までを自動抽出します。タグを作成する前に対象バージョンの見出し、分類、変更項目を記載し、次のコマンドで抽出範囲を確認してください。

```powershell
.\scripts\verify-release-notes.ps1 -Version 1.0.0
```

配布ZIPには3OSのランチャー、自動起動登録・解除、ローカル証明書／hosts登録・解除、統合インストール／解除、Windows共通ランタイムスクリプトを必ず含めます。

Android版は`android/app/build.gradle.kts`の`versionName`と一致する`android-v<version>`タグをmainのコミットへ付けます。`CHANGELOG.md`へ`## [Android <version>]`節を確定し、次のコマンドでAndroid専用リリース本文を確認します。

```powershell
.\scripts\verify-release-notes.ps1 -Version 0.2.0 -SectionPrefix Android
```

タグのpush後はAndroid Releaseワークフローが単体テスト、release Lint、Gradle依存関係のOSV監査、署名付きAPK生成、`apksigner`検証、SHA-256生成を行います。公開されたAPK名のバージョン、署名検証、SHA-256、CHANGELOG本文がタグと一致することを確認します。

更新ボタンの変更時は、設定表示直後の確認中、同一版、旧版、新版、通信失敗後の再試行、連打、設定の再表示を確認します。デスクトップの現在版はMavenが生成するbuild-infoの`revision`、Androidは`BuildConfig.VERSION_NAME`を使用します。デスクトップのSNAPSHOTは基底版で比較し、版が不明なビルドではダウンロードを開始しません。

## ロールバック

返信の取得処理を変更した場合は、`verification.md`の「返信詳細の終端」に従って、返信0件・複数返信の終端と通信失敗後の明示再試行を検証します。Androidの実機検証後は同じ署名の修正済み非debuggable release APKへ戻し、実機から取得したAPKのハッシュをビルド済みAPKと比較します。Windowsは稼働JARを退避して修正済みJARへ置換し、既存の自動起動タスクから起動したプロセス、JARのハッシュ、HTTP/HTTPSのready応答を確認します。認証・設定・証明書は変更しません。

統合インストーラーはJARとランチャーを置換前に`.bak`へ退避し、更新後のHTTP/HTTPS確認に失敗した場合は旧ファイルと旧自動起動タスクを復旧して再起動します。更新がローカル検証またはCIで失敗した場合は、失敗ログを保存したうえで更新コミットを戻します。永続データのスキーマを変更した場合は、更新前に作成したバックアップから復旧し、旧バージョンで読み込めることを確認します。共有設定は本体の欠落・破損時に`settings.json.bak`から自動復旧し、両方とも利用不能な場合は既定設定へ再初期化します。初回移行が失敗した場合は旧`localStorage`を削除せず再試行可能な状態を維持します。
