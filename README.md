# NyTweetDeck

NyTweetDeckは、ローカルで動作するカラム型Xクライアントです。Javaアプリがループバックアドレス上でAPIとWeb UIを配信し、ブラウザから利用する構成を目指しています。

現在はAndroid API接続段階です。カラム型UI、追加・削除・対象設定、編集可能なメインメニュー、OCFログインUI、複数アカウント切替、11言語選択とRTL、表示設定、暗号化Vault、OAuth付きGraphQL/REST、ホーム・通知・ユーザー・リスト・履歴・検索・トレンド・DM受信箱、ページネーション、投稿・返信・いいね・リポスト・履歴保存まで製品経路へ接続しています。投稿操作後はSSEで対象アカウントのカラムだけを更新し、定期ポーリングは行いません。実アカウント通信の検証はAndroidクライアント資格情報の抽出・利用に対する明示承認待ちです。

Android版X 12.19.1の通信仕様解析を開始しており、確認済みのAPIプロファイルとOAuth 1.0a署名基盤をJava側へ組み込んでいます。解析根拠は [docs/android-api-analysis.md](docs/android-api-analysis.md) に記録しています。

複数アカウントのOAuth資格情報は、専用VaultパスフレーズからPBKDF2-HMAC-SHA256で鍵を導出し、AES-256-GCMで暗号化して保存する構成です。Vaultの作成・解除・ロックとAndroid端末プロファイルは設定画面から操作できます。

## 必要な環境

- JDK 21以上
- Maven 3.9以上
- Bun 1.4以上

## 起動

```powershell
mvn spring-boot:run
```

起動後、ブラウザで `http://127.0.0.1:18080` を開きます。MavenはBun bundlerでフロントエンドを生成してからSpring Bootを起動します。

配布ZIPでは、Windowsは`run-nytweetdeck.cmd`、macOS/Linuxは`run-nytweetdeck.sh`を実行します。ランチャーはサーバーの準備完了を待ってからブラウザを開きます。

フロントエンドをホットリロードしながら開発する場合は、別々のターミナルで次を実行します。

```powershell
mvn spring-boot:run "-Dexec.skip=true"
```

```powershell
Set-Location .\frontend
bun run dev
```

開発画面は `http://127.0.0.1:5173` です。`/api/` へのリクエストはJavaバックエンドへ転送されます。

## 検証

```powershell
Set-Location .\frontend
bun run lint
bun run format:check
bun run type-check
bun run build
bun run test
bun audit
Set-Location ..
mvn verify
.\scripts\audit-maven.ps1
```

解析対象APKMを更新した場合は、JADX出力後に次を実行し、GraphQL操作、RESTパス、APKメタデータを再生成します。

```powershell
.\scripts\extract-android-api.ps1
```

## セキュリティ上の境界

- サーバーは既定で `127.0.0.1` のみにバインドします。
- 将来扱うXのCookie、トークン、アカウント情報はブラウザの`localStorage`へ保存しません。
- アカウントOAuth token/secretは平文保存せず、暗号化Vaultだけへ保存します。Vaultパスフレーズも永続化しません。
- 現在`localStorage`へ保存するのは、カラム配置、表示言語、テーマなどの非機密UI設定だけです。
- 解析用APKMと解析生成物はGit管理外です。

詳しい構成は [docs/architecture.md](docs/architecture.md) を参照してください。

## リリース

`v`で始まるタグをmainへプッシュすると、全検証と脆弱性監査後にJAR・ランチャー・ドキュメントをまとめたZIPとSHA-256ファイルをGitHub Releaseへ公開します。タグはリリース対象コミットを指していることを確認してから作成します。
